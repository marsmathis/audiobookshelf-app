//
//  AbsDownloader.swift
//  App
//
//  Created by advplyr on 5/13/22.
//

import Foundation
import Capacitor
import RealmSwift
import Network
import UIKit

@objc(AbsDownloader)
public class AbsDownloader: CAPPlugin, CAPBridgedPlugin, URLSessionDownloadDelegate {
    public var identifier = "AbsDownloaderPlugin"
    public var jsName = "AbsDownloader"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "setDownloadNotificationStrings", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getDownloadItems", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "downloadLibraryItem", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "pauseDownloadItem", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "resumeDownloadItem", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "pauseAllDownloadItems", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "resumeAllDownloadItems", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "allowCellularForAllDownloadItems", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "retryDownloadItem", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "cancelDownloadItem", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "cancelAllDownloadItems", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getQueueStorageStats", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "reorderDownloadItems", returnType: CAPPluginReturnPromise)
    ]

    private static let downloadsDirectory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    private static let retryDelays: [TimeInterval] = [2, 5, 10, 20, 30]
    private static let minimumFreeSpace: Int64 = 256 * 1024 * 1024

    private let stateQueue = DispatchQueue(label: "com.audiobookshelf.download-queue")
    private let pathMonitor = NWPathMonitor()
    private var pathIsAvailable = false
    private var pathIsCellular = false
    private var pathSignature = "unknown"
    private var activeTask: URLSessionDownloadTask?
    private var activePartId: String?
    private var activeURL: String?
    private var intentionalCancellations = Set<Int>()
    private var speedSamples = [String: SpeedSample]()
    private var completingItemIds = Set<String>()
    private var queueTimer: DispatchSourceTimer?

    private lazy var session: URLSession = {
        // Keep the legacy identifier so upgrades reconnect to transfers created by older builds.
        let config = URLSessionConfiguration.background(withIdentifier: "AbsDownloader")
        config.httpMaximumConnectionsPerHost = 1
        config.sessionSendsLaunchEvents = true
        config.isDiscretionary = false
        config.waitsForConnectivity = true
        let delegateQueue = OperationQueue()
        delegateQueue.name = "com.audiobookshelf.download-delegate"
        delegateQueue.maxConcurrentOperationCount = 1
        return URLSession(configuration: config, delegate: self, delegateQueue: delegateQueue)
    }()

    public override func load() {
        super.load()
        startNetworkMonitor()
        restoreQueue()
    }

    deinit {
        pathMonitor.cancel()
        queueTimer?.cancel()
    }

    // MARK: - Queue lifecycle

    private func startNetworkMonitor() {
        pathMonitor.pathUpdateHandler = { [weak self] path in
            guard let self = self else { return }
            let cellular = path.usesInterfaceType(.cellular)
            let signature = "\(path.status)-\(cellular)-\(path.usesInterfaceType(.wifi))"
            self.stateQueue.async {
                let changed = self.pathSignature != "unknown" && self.pathSignature != signature
                self.pathSignature = signature
                self.pathIsAvailable = path.status == .satisfied
                self.pathIsCellular = cellular
                if changed && self.activeTask != nil {
                    self.suspendActiveTask(reason: "Network changed, resuming")
                } else {
                    self.clearNetworkWaitsIfPossible()
                    self.startNextDownload()
                }
            }
        }
        pathMonitor.start(queue: DispatchQueue(label: "com.audiobookshelf.network-monitor"))
    }

    private func restoreQueue() {
        _ = session
        session.getAllTasks { [weak self] tasks in
            guard let self = self else { return }
            self.stateQueue.async {
                let downloads = tasks.compactMap { $0 as? URLSessionDownloadTask }
                if downloads.isEmpty {
                    self.preparePersistedQueueForRestore()
                    self.finishRestoredCompletedItems()
                    self.startNextDownload()
                    self.notifyQueueChanged()
                    return
                }

                let group = DispatchGroup()
                for task in downloads {
                    group.enter()
                    self.intentionalCancellations.insert(task.taskIdentifier)
                    task.cancel { resumeData in
                        self.stateQueue.async {
                            if let partId = task.taskDescription {
                                self.persistResumeData(resumeData, for: partId)
                            }
                            group.leave()
                        }
                    }
                }
                group.notify(queue: self.stateQueue) {
                    self.preparePersistedQueueForRestore()
                    self.finishRestoredCompletedItems()
                    self.startNextDownload()
                    self.notifyQueueChanged()
                }
            }
        }
    }

    private func preparePersistedQueueForRestore() {
        do {
            let realm = try Realm()
            let items = realm.objects(DownloadItem.self)
            let malformedItems = items.filter { $0.id == nil || $0.downloadItemParts.isEmpty }
            try realm.write {
                for item in items {
                    for part in item.downloadItemParts {
                        if part.serverPath?.hasPrefix("/") != true,
                           let uri = part.uri, let components = URLComponents(string: uri), components.path.hasPrefix("/api/") {
                            let nonTokenQuery = components.queryItems?.filter { $0.name != "token" }
                            var migratedPath = components.path
                            if let nonTokenQuery = nonTokenQuery, !nonTokenQuery.isEmpty {
                                var relativeComponents = URLComponents()
                                relativeComponents.queryItems = nonTokenQuery
                                migratedPath += relativeComponents.percentEncodedQuery.map { "?\($0)" } ?? ""
                            }
                            part.serverPath = migratedPath
                        }
                        let file = destinationURL(for: part)
                        let fileSize = file.flatMap { try? $0.resourceValues(forKeys: [.fileSizeKey]).fileSize }.map(Double.init) ?? 0
                        let hasCompleteFile = fileSize > 0 && (part.fileSize <= 0 || fileSize == part.fileSize)
                        if hasCompleteFile {
                            part.bytesDownloaded = part.fileSize > 0 ? part.fileSize : fileSize
                            part.progress = 100
                            part.completed = true
                            part.moved = true
                            part.failed = false
                            part.resumeData = nil
                            continue
                        }
                        if part.completed || part.moved {
                            part.completed = false
                            part.moved = false
                            part.bytesDownloaded = 0
                            part.progress = 0
                        }
                        part.downloadId = nil
                        part.isMoving = false
                        part.bytesPerSecond = 0
                        if item.terminalFailureAt == nil {
                            part.failed = false
                            part.waitingForNetwork = false
                            part.waitingForWifi = false
                            part.waitingForRetry = false
                            part.waitingForSpace = false
                            part.retryAfterTime = nil
                            part.lastError = nil
                        }
                    }
                }
                for item in malformedItems {
                    realm.delete(item.downloadItemParts)
                    realm.delete(item)
                }
                normalizeQueuePositions(items: Array(items.sorted(byKeyPath: "queuePosition")))
            }
            let restoredItems = Array(items.sorted(byKeyPath: "queuePosition"))
            restoredItems.forEach(notifyItem)
            restoredItems.flatMap { Array($0.downloadItemParts) }.forEach(notifyPart)
        } catch {
            AbsLogger.error(message: "Failed to restore download queue", error: error)
        }
    }

    private func finishRestoredCompletedItems() {
        do {
            let realm = try Realm()
            let completedIds = realm.objects(DownloadItem.self).filter { !$0.downloadItemParts.isEmpty && $0.isDoneDownloading() }.compactMap(\.id)
            completedIds.forEach { completeItemIfNeeded(downloadItemId: $0) }
        } catch {
            AbsLogger.error(message: "Failed to finalize restored downloads", error: error)
        }
    }

    private func startNextDownload() {
        guard activeTask == nil else {
            restartForChangedServerAddressIfNeeded()
            return
        }

        do {
            let realm = try Realm()
            let items = Array(realm.objects(DownloadItem.self).sorted(byKeyPath: "queuePosition"))
            guard let item = items.first(where: { !$0.isPaused && $0.terminalFailureAt == nil && $0.downloadItemParts.contains(where: { !$0.completed && !$0.moved && !$0.failed }) }) else {
                cancelQueueTimer()
                notifyQueueChanged()
                return
            }
            guard let part = item.downloadItemParts.first(where: { !$0.completed && !$0.moved && !$0.failed }) else { return }

            if !pathIsAvailable {
                markWaiting(partId: part.id, network: true, wifi: false, message: "Waiting for network")
                scheduleQueueUpdate(after: 1)
                return
            }
            if pathIsCellular && !item.allowCellularDownload {
                markWaiting(partId: part.id, network: true, wifi: true, message: "Waiting for Wi-Fi")
                scheduleQueueUpdate(after: 1)
                return
            }
            if part.waitingForRetry, let retryAt = part.retryAfterTime {
                let delay = max(0, retryAt / 1000 - Date().timeIntervalSince1970)
                if delay > 0 {
                    scheduleQueueUpdate(after: delay)
                    return
                }
            }
            guard hasAvailableSpace(for: part) else {
                try realm.write {
                    part.waitingForSpace = true
                    part.waitingForNetwork = false
                    part.waitingForWifi = false
                    part.waitingForRetry = false
                    part.retryAfterTime = nil
                    part.lastError = "Waiting for available storage"
                    part.bytesPerSecond = 0
                }
                notifyPart(part)
                scheduleQueueUpdate(after: 5)
                return
            }

            guard let url = currentURL(for: part) else {
                failPermanently(partId: part.id, message: "Invalid download URL")
                return
            }
            let task: URLSessionDownloadTask
            let canUseResumeData = part.uri == url.absoluteString
            if let resumeData = part.resumeData, !resumeData.isEmpty, canUseResumeData {
                task = session.downloadTask(withResumeData: resumeData)
            } else {
                var request = URLRequest(url: url)
                request.allowsCellularAccess = item.allowCellularDownload
                request.timeoutInterval = 60
                task = session.downloadTask(with: request)
            }
            task.taskDescription = part.id
            try realm.write {
                part.downloadId = task.taskIdentifier
                part.failed = false
                part.isMoving = false
                part.waitingForSpace = false
                part.waitingForNetwork = false
                part.waitingForWifi = false
                part.waitingForRetry = false
                part.retryAfterTime = nil
                part.lastError = nil
                part.bytesPerSecond = 0
                part.resumeData = nil
                part.uri = url.absoluteString
                if !canUseResumeData {
                    part.bytesDownloaded = 0
                    part.progress = 0
                }
            }
            activeTask = task
            activePartId = part.id
            activeURL = url.absoluteString
            speedSamples[part.id] = SpeedSample(timestamp: Date().timeIntervalSince1970, bytes: part.bytesDownloaded)
            notifyPart(part)
            notifyQueueChanged()
            task.resume()
            scheduleQueueUpdate(after: 1)
        } catch {
            AbsLogger.error(message: "Failed to start queued download", error: error)
            scheduleQueueUpdate(after: 2)
        }
    }

    private func scheduleQueueUpdate(after delay: TimeInterval) {
        cancelQueueTimer()
        let timer = DispatchSource.makeTimerSource(queue: stateQueue)
        timer.schedule(deadline: .now() + max(0.1, delay))
        timer.setEventHandler { [weak self] in
            self?.queueTimer = nil
            self?.startNextDownload()
        }
        queueTimer = timer
        timer.resume()
    }

    private func cancelQueueTimer() {
        queueTimer?.cancel()
        queueTimer = nil
    }

    private func restartForChangedServerAddressIfNeeded() {
        guard let partId = activePartId,
              let item = Database.shared.getDownloadItem(downloadItemPartId: partId),
              let part = item.downloadItemParts.first(where: { $0.id == partId }),
              let current = currentURL(for: part)?.absoluteString,
              current != activeURL else {
            scheduleQueueUpdate(after: 1)
            return
        }
        suspendActiveTask(reason: "Connection changed, resuming", preserveResumeData: false)
    }

    private func suspendActiveTask(reason: String, preserveResumeData: Bool = true) {
        guard let task = activeTask, let partId = activePartId else {
            startNextDownload()
            return
        }
        intentionalCancellations.insert(task.taskIdentifier)
        activeTask = nil
        activePartId = nil
        activeURL = nil
        speedSamples.removeValue(forKey: partId)
        setPartIdle(partId: partId, message: reason)
        task.cancel { [weak self] resumeData in
            self?.stateQueue.async {
                self?.persistResumeData(preserveResumeData ? resumeData : nil, for: partId)
                if !preserveResumeData { self?.resetProgress(partId: partId) }
                self?.startNextDownload()
            }
        }
    }

    private func clearNetworkWaitsIfPossible() {
        guard pathIsAvailable else { return }
        do {
            let realm = try Realm()
            let parts = realm.objects(DownloadItemPart.self).filter("waitingForNetwork == true")
            let changed = parts.filter { part in
                guard let item = Database.shared.getDownloadItem(downloadItemPartId: part.id) else { return false }
                return !pathIsCellular || item.allowCellularDownload
            }
            guard !changed.isEmpty else { return }
            try realm.write {
                for part in changed {
                    part.waitingForNetwork = false
                    part.waitingForWifi = false
                    part.lastError = nil
                }
            }
            changed.forEach(notifyPart)
        } catch {
            AbsLogger.error(message: "Failed to clear network wait state", error: error)
        }
    }

    // MARK: - URLSession delegates

    public func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        guard let partId = downloadTask.taskDescription else { return }
        stateQueue.async { [weak self] in
            self?.updateProgress(partId: partId, totalBytesWritten: totalBytesWritten, totalBytesExpected: totalBytesExpectedToWrite)
        }
    }

    public func urlSession(_ session: URLSession, taskIsWaitingForConnectivity task: URLSessionTask) {
        guard let partId = task.taskDescription else { return }
        stateQueue.async { [weak self] in
            guard let self = self else { return }
            self.markActiveWaiting(partId: partId, wifi: self.pathIsCellular)
        }
    }

    public func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let partId = downloadTask.taskDescription else { return }
        stateQueue.sync {
            do {
                if let response = downloadTask.response as? HTTPURLResponse, !(200..<300).contains(response.statusCode) {
                    throw LibraryItemDownloadError.failedDownload("HTTP \(response.statusCode)")
                }
                let realm = try Realm()
                guard let part = realm.object(ofType: DownloadItemPart.self, forPrimaryKey: partId),
                      let destination = destinationURL(for: part) else {
                    throw LibraryItemDownloadError.downloadItemPartNotFound
                }
                try FileManager.default.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
                if FileManager.default.fileExists(atPath: destination.path) {
                    try FileManager.default.removeItem(at: destination)
                }
                try FileManager.default.moveItem(at: location, to: destination)
                let size = (try? destination.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Double.init) ?? part.bytesDownloaded
                try realm.write {
                    part.bytesDownloaded = part.fileSize > 0 ? part.fileSize : size
                    part.progress = 100
                    part.completed = true
                    part.moved = true
                    part.isMoving = false
                    part.failed = false
                    part.downloadId = nil
                    part.waitingForSpace = false
                    part.waitingForNetwork = false
                    part.waitingForWifi = false
                    part.waitingForRetry = false
                    part.retryAfterTime = nil
                    part.lastError = nil
                    part.bytesPerSecond = 0
                    part.retryCount = 0
                    part.resumeData = nil
                }
                notifyPart(part)
                completeItemIfNeeded(downloadItemId: part.downloadItemId)
            } catch {
                failOrRetry(partId: partId, error: error)
            }
        }
    }

    public func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let partId = task.taskDescription else { return }
        stateQueue.async { [weak self] in
            guard let self = self else { return }
            let intentional = self.intentionalCancellations.remove(task.taskIdentifier) != nil
            if self.activeTask?.taskIdentifier == task.taskIdentifier {
                self.activeTask = nil
                self.activePartId = nil
                self.activeURL = nil
            }
            self.speedSamples.removeValue(forKey: partId)
            if let error = error, !intentional {
                let resumeData = (error as NSError).userInfo[NSURLSessionDownloadTaskResumeData] as? Data
                if let resumeData = resumeData { self.persistResumeData(resumeData, for: partId) }
                self.failOrRetry(partId: partId, error: error)
            }
            self.startNextDownload()
        }
    }

    public func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        DispatchQueue.main.async {
            guard let appDelegate = UIApplication.shared.delegate as? AppDelegate,
                  let completionHandler = appDelegate.backgroundCompletionHandler else { return }
            appDelegate.backgroundCompletionHandler = nil
            completionHandler()
        }
    }

    private func updateProgress(partId: String, totalBytesWritten: Int64, totalBytesExpected: Int64) {
        do {
            let realm = try Realm()
            guard let part = realm.object(ofType: DownloadItemPart.self, forPrimaryKey: partId) else { return }
            let now = Date().timeIntervalSince1970
            let previous = speedSamples[partId]
            var speed = part.bytesPerSecond
            if let previous = previous, now - previous.timestamp >= 0.5, Double(totalBytesWritten) >= previous.bytes {
                let sample = (Double(totalBytesWritten) - previous.bytes) / (now - previous.timestamp)
                speed = speed <= 0 ? sample : (speed * 3 + sample) / 4
                speedSamples[partId] = SpeedSample(timestamp: now, bytes: Double(totalBytesWritten))
            }
            let expected = totalBytesExpected > 0 ? Double(totalBytesExpected) : part.fileSize
            try realm.write {
                part.downloadId = activeTask?.taskIdentifier
                part.bytesDownloaded = Double(totalBytesWritten)
                if expected > 0 {
                    part.progress = min(100, Double(totalBytesWritten) / expected * 100)
                }
                part.bytesPerSecond = max(0, speed)
                part.waitingForNetwork = false
                part.waitingForWifi = false
                part.lastError = nil
            }
            notifyPart(part)
        } catch {
            AbsLogger.error(message: "Failed to update download progress", error: error)
        }
    }

    private func failOrRetry(partId: String, error: Error) {
        do {
            let realm = try Realm()
            guard let part = realm.object(ofType: DownloadItemPart.self, forPrimaryKey: partId), !part.completed else { return }
            let nsError = error as NSError
            if nsError.domain == NSURLErrorDomain && [NSURLErrorNotConnectedToInternet, NSURLErrorNetworkConnectionLost, NSURLErrorCannotFindHost, NSURLErrorCannotConnectToHost, NSURLErrorDNSLookupFailed].contains(nsError.code) && !pathIsAvailable {
                markWaiting(partId: partId, network: true, wifi: false, message: "Waiting for network")
                return
            }
            let retryCount = part.retryCount + 1
            if retryCount > Self.retryDelays.count {
                try realm.write {
                    part.failed = true
                    part.completed = false
                    part.downloadId = nil
                    part.waitingForNetwork = false
                    part.waitingForWifi = false
                    part.waitingForRetry = false
                    part.waitingForSpace = false
                    part.retryAfterTime = nil
                    part.lastError = error.localizedDescription
                    part.bytesPerSecond = 0
                    part.resumeData = nil
                    if let item = realm.object(ofType: DownloadItem.self, forPrimaryKey: part.downloadItemId ?? "") {
                        item.terminalFailureAt = Date().timeIntervalSince1970 * 1000
                    }
                }
                notifyPart(part)
                notifyQueueChanged()
                return
            }
            let delay = Self.retryDelays[retryCount - 1]
            try realm.write {
                part.retryCount = retryCount
                part.failed = false
                part.completed = false
                part.downloadId = nil
                part.waitingForNetwork = false
                part.waitingForWifi = false
                part.waitingForRetry = true
                part.waitingForSpace = false
                part.retryAfterTime = (Date().timeIntervalSince1970 + delay) * 1000
                part.lastError = error.localizedDescription
                part.bytesPerSecond = 0
            }
            notifyPart(part)
            scheduleQueueUpdate(after: delay)
        } catch {
            AbsLogger.error(message: "Failed to record download error", error: error)
        }
    }

    private func failPermanently(partId: String, message: String) {
        failOrRetry(partId: partId, error: LibraryItemDownloadError.failedDownload(message))
    }

    // MARK: - State helpers

    private func markWaiting(partId: String, network: Bool, wifi: Bool, message: String) {
        do {
            let realm = try Realm()
            guard let part = realm.object(ofType: DownloadItemPart.self, forPrimaryKey: partId) else { return }
            try realm.write {
                part.downloadId = nil
                part.waitingForNetwork = network
                part.waitingForWifi = wifi
                part.waitingForRetry = false
                part.waitingForSpace = false
                part.retryAfterTime = nil
                part.lastError = message
                part.bytesPerSecond = 0
            }
            notifyPart(part)
        } catch {
            AbsLogger.error(message: "Failed to update download wait state", error: error)
        }
    }

    private func markActiveWaiting(partId: String, wifi: Bool) {
        do {
            let realm = try Realm()
            guard let part = realm.object(ofType: DownloadItemPart.self, forPrimaryKey: partId) else { return }
            try realm.write {
                part.waitingForNetwork = true
                part.waitingForWifi = wifi
                part.waitingForRetry = false
                part.waitingForSpace = false
                part.retryAfterTime = nil
                part.lastError = wifi ? "Waiting for Wi-Fi" : "Waiting for network"
                part.bytesPerSecond = 0
            }
            notifyPart(part)
        } catch {
            AbsLogger.error(message: "Failed to mark active download as waiting", error: error)
        }
    }

    private func setPartIdle(partId: String, message: String?) {
        do {
            let realm = try Realm()
            guard let part = realm.object(ofType: DownloadItemPart.self, forPrimaryKey: partId) else { return }
            try realm.write {
                part.downloadId = nil
                part.waitingForNetwork = false
                part.waitingForWifi = false
                part.waitingForRetry = false
                part.waitingForSpace = false
                part.retryAfterTime = nil
                part.lastError = message
                part.bytesPerSecond = 0
            }
            notifyPart(part)
        } catch {
            AbsLogger.error(message: "Failed to reset download part", error: error)
        }
    }

    private func persistResumeData(_ data: Data?, for partId: String) {
        do {
            let realm = try Realm()
            guard let part = realm.object(ofType: DownloadItemPart.self, forPrimaryKey: partId), !part.completed else { return }
            try realm.write { part.resumeData = data }
        } catch {
            AbsLogger.error(message: "Failed to persist download resume data", error: error)
        }
    }

    private func resetProgress(partId: String) {
        do {
            let realm = try Realm()
            guard let part = realm.object(ofType: DownloadItemPart.self, forPrimaryKey: partId), !part.completed else { return }
            try realm.write {
                part.bytesDownloaded = 0
                part.progress = 0
                part.bytesPerSecond = 0
            }
            notifyPart(part)
        } catch {
            AbsLogger.error(message: "Failed to reset download progress", error: error)
        }
    }

    private func notifyPart(_ part: DownloadItemPart) {
        if let dictionary = try? part.asDictionary() {
            notifyListeners("onDownloadItemPartUpdate", data: dictionary)
        }
    }

    private func notifyItem(_ item: DownloadItem) {
        if let dictionary = try? item.asDictionary() {
            notifyListeners("onDownloadItem", data: dictionary)
        }
    }

    private func notifyQueueChanged() {
        let hasWork = ((try? Realm().objects(DownloadItem.self).count) ?? 0) > 0
        notifyListeners("onQueueChanged", data: ["hasWork": hasWork])
    }

    private func normalizeQueuePositions(items: [DownloadItem]) {
        for (index, item) in items.enumerated() { item.queuePosition = index }
    }

    private func currentURL(for part: DownloadItemPart) -> URL? {
        guard let serverPath = part.serverPath else { return part.downloadURL }
        if serverPath.hasPrefix("/") {
            guard let config = Store.serverConfig else { return part.downloadURL }
            var value = "\(config.resolvedAddress)\(serverPath)"
            value += value.contains("?") ? "&token=\(config.token)" : "?token=\(config.token)"
            if serverPath.hasSuffix("/cover") { value += "&format=jpeg" }
            return URL(string: value)
        }
        return part.downloadURL
    }

    private func destinationURL(for part: DownloadItemPart) -> URL? {
        guard let relativePath = part.destinationUri else { return nil }
        let url = Self.downloadsDirectory.appendingPathComponent(relativePath).standardizedFileURL
        guard url.path.hasPrefix(Self.downloadsDirectory.standardizedFileURL.path + "/") else { return nil }
        return url
    }

    private func hasAvailableSpace(for part: DownloadItemPart) -> Bool {
        let remaining = max(0, Int64(part.fileSize - part.bytesDownloaded))
        guard let values = try? Self.downloadsDirectory.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey, .volumeTotalCapacityKey]) else { return true }
        let available = values.volumeAvailableCapacityForImportantUsage ?? Int64(values.volumeAvailableCapacity ?? 0)
        let total = Int64(values.volumeTotalCapacity ?? 0)
        let headroom = max(Self.minimumFreeSpace, total / 20)
        return available >= remaining + headroom
    }

    private func deleteFiles(for item: DownloadItem) {
        for part in item.downloadItemParts {
            guard let url = destinationURL(for: part) else { continue }
            try? FileManager.default.removeItem(at: url)
        }
        if let id = item.libraryItemId {
            let folder = Self.downloadsDirectory.appendingPathComponent(id).standardizedFileURL
            if folder.path.hasPrefix(Self.downloadsDirectory.standardizedFileURL.path + "/") {
                try? FileManager.default.removeItem(at: folder)
            }
        }
    }

    // MARK: - Completion

    private func completeItemIfNeeded(downloadItemId: String?) {
        guard let downloadItemId = downloadItemId, !completingItemIds.contains(downloadItemId),
              let item = Database.shared.getDownloadItem(downloadItemId: downloadItemId), item.isDoneDownloading() else { return }
        completingItemIds.insert(downloadItemId)
        handleDownloadTaskCompleteFromDownloadItem(item.freeze()) { [weak self] in
            self?.stateQueue.async {
                if let stored = Database.shared.getDownloadItem(downloadItemId: downloadItemId) { try? stored.delete() }
                self?.completingItemIds.remove(downloadItemId)
                self?.normalizePersistedQueue()
                self?.notifyQueueChanged()
                self?.startNextDownload()
            }
        }
    }

    private func handleDownloadTaskCompleteFromDownloadItem(_ downloadItem: DownloadItem, completion: @escaping () -> Void) {
        var statusNotification = [String: Any]()
        statusNotification["libraryItemId"] = downloadItem.id
        guard downloadItem.didDownloadSuccessfully(), let libraryItemId = downloadItem.libraryItemId else {
            notifyListeners("onItemDownloadComplete", data: statusNotification)
            completion()
            return
        }
        ApiClient.getLibraryItemWithProgress(libraryItemId: libraryItemId, episodeId: downloadItem.episodeId) { [weak self] libraryItem in
            defer { completion() }
            guard let self = self, let libraryItem = libraryItem else {
                AbsLogger.error(message: "LibraryItem not found after download")
                self?.notifyListeners("onItemDownloadComplete", data: statusNotification)
                return
            }
            var coverFile: String?
            let files = downloadItem.downloadItemParts.compactMap { part -> LocalFile? in
                guard let filename = part.filename, let destination = part.destinationUri,
                      let destinationURL = self.destinationURL(for: part) else { return nil }
                var mimeType = part.mimeType()
                if filename == "cover.jpg" { coverFile = destination; mimeType = "image/jpg" }
                return LocalFile(libraryItem.id, filename, mimeType ?? "application/octet-stream", destination, fileSize: Int(destinationURL.fileSize))
            }
            var localItem = Database.shared.getLocalLibraryItem(byServerLibraryItemId: libraryItem.id)
            if let existing = localItem, existing.isPodcast {
                try? Realm().write { try? existing.addFiles(files, item: libraryItem) }
            } else {
                localItem = LocalLibraryItem(libraryItem, localUrl: libraryItem.id, server: Store.serverConfig!, files: files, coverPath: coverFile)
                if let localItem = localItem { try? Database.shared.saveLocalLibraryItem(localLibraryItem: localItem) }
            }
            if let localItem = localItem {
                statusNotification["localLibraryItem"] = try? localItem.asDictionary()
                if let progress = libraryItem.userMediaProgress {
                    let episode = downloadItem.media?.episodes.first(where: { $0.id == downloadItem.episodeId })
                    let localProgress = LocalMediaProgress(localLibraryItem: localItem, episode: episode, progress: progress)
                    try? localProgress.save()
                    statusNotification["localMediaProgress"] = try? localProgress.asDictionary()
                }
            }
            self.notifyListeners("onItemDownloadComplete", data: statusNotification)
        }
    }

    private func normalizePersistedQueue() {
        do {
            let realm = try Realm()
            let items = Array(realm.objects(DownloadItem.self).sorted(byKeyPath: "queuePosition"))
            try realm.write { normalizeQueuePositions(items: items) }
            items.forEach(notifyItem)
        } catch {
            AbsLogger.error(message: "Failed to normalize download queue", error: error)
        }
    }

    // MARK: - Capacitor methods

    @objc func setDownloadNotificationStrings(_ call: CAPPluginCall) {
        // iOS owns background-transfer presentation; keep the cross-platform API symmetrical.
        call.resolve()
    }

    @objc func getDownloadItems(_ call: CAPPluginCall) {
        stateQueue.async {
            do {
                let items = Array(try Realm().objects(DownloadItem.self).sorted(byKeyPath: "queuePosition"))
                call.resolve(["items": items.compactMap { try? $0.asDictionary() }])
            } catch {
                call.resolve(["items": []])
            }
        }
    }

    @objc func downloadLibraryItem(_ call: CAPPluginCall) {
        guard let libraryItemId = call.getString("libraryItemId") else {
            call.resolve(["error": "libraryItemId not specified"])
            return
        }
        var episodeId = call.getString("episodeId")
        if episodeId == "null" { episodeId = nil }
        let allowCellular = call.getBool("allowCellularDownload") ?? false
        let downloadId = episodeId.map { "\(libraryItemId)-\($0)" } ?? libraryItemId
        if Database.shared.getDownloadItem(downloadItemId: downloadId) != nil {
            call.resolve(["error": "Download already started for this media entity"])
            return
        }
        ApiClient.getLibraryItemWithProgress(libraryItemId: libraryItemId, episodeId: episodeId) { [weak self] libraryItem in
            guard let self = self, let libraryItem = libraryItem else {
                call.resolve(["error": "Server request failed"])
                return
            }
            self.stateQueue.async {
                do {
                    let episode = episodeId.flatMap { id in libraryItem.media?.episodes.first(where: { $0.id == id }) }
                    try self.enqueueLibraryItem(libraryItem, episode: episode, allowCellular: allowCellular)
                    call.resolve()
                } catch {
                    AbsLogger.error(message: "Failed to enqueue download", error: error)
                    call.resolve(["error": "Failed to download"])
                }
            }
        }
    }

    @objc func pauseDownloadItem(_ call: CAPPluginCall) {
        mutatePause(downloadItemId: call.getString("downloadItemId") ?? "", paused: true, call: call)
    }

    @objc func resumeDownloadItem(_ call: CAPPluginCall) {
        mutatePause(downloadItemId: call.getString("downloadItemId") ?? "", paused: false, call: call)
    }

    private func mutatePause(downloadItemId: String, paused: Bool, call: CAPPluginCall) {
        stateQueue.async {
            do {
                let realm = try Realm()
                guard let item = realm.object(ofType: DownloadItem.self, forPrimaryKey: downloadItemId), item.isPaused != paused else {
                    call.resolve(["value": false]); return
                }
                let activeBelongsToItem = self.activePartId.map { id in item.downloadItemParts.contains(where: { $0.id == id }) } ?? false
                try realm.write {
                    item.isPaused = paused
                    for part in item.downloadItemParts where !part.completed && !part.failed {
                        part.waitingForNetwork = false
                        part.waitingForWifi = false
                        part.waitingForRetry = false
                        part.waitingForSpace = false
                        part.retryAfterTime = nil
                        part.lastError = paused ? "Paused" : nil
                        part.bytesPerSecond = 0
                    }
                }
                self.notifyItem(item)
                item.downloadItemParts.forEach(self.notifyPart)
                if activeBelongsToItem && paused {
                    self.suspendActiveTask(reason: "Paused")
                } else if !paused {
                    let firstRunnable = Array(realm.objects(DownloadItem.self).sorted(byKeyPath: "queuePosition")).first(where: {
                        !$0.isPaused && $0.terminalFailureAt == nil && $0.downloadItemParts.contains(where: { !$0.completed && !$0.moved && !$0.failed })
                    })
                    if firstRunnable?.id == item.id && self.activePartId != nil { self.suspendActiveTask(reason: "Queue priority changed") }
                    else { self.startNextDownload() }
                }
                self.notifyQueueChanged()
                call.resolve(["value": true])
            } catch {
                call.resolve(["value": false])
            }
        }
    }

    @objc func pauseAllDownloadItems(_ call: CAPPluginCall) { mutateAllPaused(true, call: call) }
    @objc func resumeAllDownloadItems(_ call: CAPPluginCall) { mutateAllPaused(false, call: call) }

    private func mutateAllPaused(_ paused: Bool, call: CAPPluginCall) {
        stateQueue.async {
            do {
                let realm = try Realm()
                let items = realm.objects(DownloadItem.self).filter("isPaused == %@", !paused)
                guard !items.isEmpty else { call.resolve(["value": false]); return }
                let snapshots = Array(items)
                try realm.write {
                    for item in snapshots where item.terminalFailureAt == nil {
                        item.isPaused = paused
                        for part in item.downloadItemParts where !part.completed && !part.failed {
                            part.waitingForNetwork = false; part.waitingForWifi = false; part.waitingForRetry = false; part.waitingForSpace = false
                            part.retryAfterTime = nil; part.lastError = paused ? "Paused" : nil; part.bytesPerSecond = 0
                        }
                    }
                }
                snapshots.forEach(self.notifyItem)
                snapshots.flatMap { Array($0.downloadItemParts) }.forEach(self.notifyPart)
                if paused { self.suspendActiveTask(reason: "Paused") } else { self.startNextDownload() }
                self.notifyQueueChanged()
                call.resolve(["value": true])
            } catch { call.resolve(["value": false]) }
        }
    }

    @objc func allowCellularForAllDownloadItems(_ call: CAPPluginCall) {
        stateQueue.async {
            do {
                let realm = try Realm()
                let items = Array(realm.objects(DownloadItem.self).filter("allowCellularDownload == false AND terminalFailureAt == nil"))
                guard !items.isEmpty else { call.resolve(["value": false]); return }
                let changedIds = Set(items.compactMap(\.id))
                let activePolicyChanged = self.activePartId.flatMap { Database.shared.getDownloadItem(downloadItemPartId: $0)?.id }.map { changedIds.contains($0) } ?? false
                try realm.write {
                    for item in items {
                        item.allowCellularDownload = true
                        for part in item.downloadItemParts where !part.completed {
                            part.waitingForWifi = false; part.waitingForNetwork = false; part.lastError = nil
                            // URLSession resume data retains the original request's cellular policy.
                            // Recreate the request so an explicit user approval takes effect.
                            part.resumeData = nil; part.bytesDownloaded = 0; part.progress = 0
                        }
                    }
                }
                items.forEach(self.notifyItem)
                items.flatMap { Array($0.downloadItemParts) }.forEach(self.notifyPart)
                if activePolicyChanged { self.suspendActiveTask(reason: "Cellular downloads enabled", preserveResumeData: false) }
                else { self.startNextDownload() }
                call.resolve(["value": true])
            } catch { call.resolve(["value": false]) }
        }
    }

    @objc func retryDownloadItem(_ call: CAPPluginCall) {
        let id = call.getString("downloadItemId") ?? ""
        stateQueue.async {
            do {
                let realm = try Realm()
                guard let item = realm.object(ofType: DownloadItem.self, forPrimaryKey: id), item.terminalFailureAt != nil || item.downloadItemParts.contains(where: { $0.failed }) else {
                    call.resolve(["value": false]); return
                }
                try realm.write {
                    item.terminalFailureAt = nil; item.isPaused = false
                    for part in item.downloadItemParts where part.failed {
                        part.failed = false; part.completed = false; part.retryCount = 0; part.retryAfterTime = nil
                        part.waitingForRetry = false; part.lastError = nil; part.bytesPerSecond = 0
                    }
                }
                self.notifyItem(item); item.downloadItemParts.forEach(self.notifyPart)
                self.startNextDownload(); self.notifyQueueChanged()
                call.resolve(["value": true])
            } catch { call.resolve(["value": false]) }
        }
    }

    @objc func cancelDownloadItem(_ call: CAPPluginCall) {
        cancel(downloadItemId: call.getString("downloadItemId") ?? "", call: call)
    }

    private func cancel(downloadItemId: String, call: CAPPluginCall?) {
        stateQueue.async {
            do {
                let realm = try Realm()
                guard let item = realm.object(ofType: DownloadItem.self, forPrimaryKey: downloadItemId) else {
                    call?.resolve(["value": false]); return
                }
                if let task = self.activeTask, let activeId = self.activePartId, item.downloadItemParts.contains(where: { $0.id == activeId }) {
                    self.intentionalCancellations.insert(task.taskIdentifier); task.cancel()
                    self.activeTask = nil; self.activePartId = nil; self.activeURL = nil
                }
                self.deleteFiles(for: item)
                try realm.write { realm.delete(item.downloadItemParts); realm.delete(item) }
                self.normalizePersistedQueue(); self.notifyQueueChanged(); self.startNextDownload()
                call?.resolve(["value": true])
            } catch { call?.resolve(["value": false]) }
        }
    }

    @objc func cancelAllDownloadItems(_ call: CAPPluginCall) {
        stateQueue.async {
            do {
                if let task = self.activeTask { self.intentionalCancellations.insert(task.taskIdentifier); task.cancel() }
                self.activeTask = nil; self.activePartId = nil; self.activeURL = nil
                let realm = try Realm()
                let items = Array(realm.objects(DownloadItem.self))
                items.forEach(self.deleteFiles)
                try realm.write {
                    for item in items { realm.delete(item.downloadItemParts); realm.delete(item) }
                }
                self.notifyQueueChanged(); call.resolve(["value": true])
            } catch { call.resolve(["value": false]) }
        }
    }

    @objc func reorderDownloadItems(_ call: CAPPluginCall) {
        guard let ids = call.getArray("downloadItemIds", String.self) else { call.resolve(["value": false]); return }
        stateQueue.async {
            do {
                let realm = try Realm()
                let items = Array(realm.objects(DownloadItem.self))
                guard ids.count == items.count, Set(ids).count == ids.count, Set(ids) == Set(items.compactMap(\.id)) else {
                    call.resolve(["value": false]); return
                }
                let byId = Dictionary(uniqueKeysWithValues: items.compactMap { item in item.id.map { ($0, item) } })
                try realm.write { for (index, id) in ids.enumerated() { byId[id]?.queuePosition = index } }
                ids.compactMap { byId[$0] }.forEach(self.notifyItem)
                let firstActive = ids.compactMap { byId[$0] }.first(where: {
                    !$0.isPaused && $0.terminalFailureAt == nil && $0.downloadItemParts.contains(where: { !$0.completed && !$0.moved && !$0.failed })
                })
                if let activeId = self.activePartId, firstActive?.downloadItemParts.contains(where: { $0.id == activeId }) != true {
                    self.suspendActiveTask(reason: "Queue priority changed")
                } else { self.startNextDownload() }
                call.resolve(["value": true])
            } catch { call.resolve(["value": false]) }
        }
    }

    @objc func getQueueStorageStats(_ call: CAPPluginCall) {
        stateQueue.async {
            do {
                let realm = try Realm()
                let parts = realm.objects(DownloadItemPart.self).filter("completed == false")
                let required = parts.reduce(Int64(0)) { $0 + max(0, Int64($1.fileSize - $1.bytesDownloaded)) }
                let unknown = parts.filter("fileSize <= 0").count
                let values = try Self.downloadsDirectory.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey, .volumeAvailableCapacityKey, .volumeTotalCapacityKey])
                let available = values.volumeAvailableCapacityForImportantUsage ?? Int64(values.volumeAvailableCapacity ?? 0)
                let total = Int64(values.volumeTotalCapacity ?? 0)
                let headroom = max(Self.minimumFreeSpace, total / 20)
                call.resolve(["locations": [["id": "internal", "requiredBytes": required, "availableBytes": available, "unknownPartCount": unknown, "hasSufficientSpace": available >= required + headroom]]])
            } catch { call.resolve(["locations": []]) }
        }
    }

    // MARK: - Download item construction

    private func enqueueLibraryItem(_ item: LibraryItem, episode: PodcastEpisode?, allowCellular: Bool) throws {
        let tracks = List<AudioTrack>()
        var episodeId: String?
        switch item.mediaType {
        case "book":
            guard item.media?.tracks.count ?? 0 > 0 || item.media?.ebookFile != nil else { throw LibraryItemDownloadError.noTracks }
            item.media?.tracks.forEach { if let copy = AudioTrack.detachCopy(of: $0) { tracks.append(copy) } }
        case "podcast":
            guard let episode = episode, let track = episode.audioTrack else { throw LibraryItemDownloadError.podcastEpisodeNotFound }
            episodeId = episode.id
            if let copy = AudioTrack.detachCopy(of: track) { tracks.append(copy) }
        default: throw LibraryItemDownloadError.unknownMediaType
        }
        let downloadItem = DownloadItem(libraryItem: item, episodeId: episodeId, server: Store.serverConfig!)
        downloadItem.allowCellularDownload = allowCellular
        downloadItem.queuePosition = (try Realm()).objects(DownloadItem.self).count
        for track in tracks {
            downloadItem.downloadItemParts.append(try makeTrackPart(downloadItemId: downloadItem.id!, item: item, track: track, episode: episode))
        }
        if let ebook = item.media?.ebookFile { downloadItem.downloadItemParts.append(try makeEbookPart(downloadItemId: downloadItem.id!, item: item, ebookFile: ebook)) }
        if let cover = item.media?.coverPath, !cover.isEmpty, let part = try? makeCoverPart(downloadItemId: downloadItem.id!, item: item) { downloadItem.downloadItemParts.append(part) }
        try Database.shared.saveDownloadItem(downloadItem)
        notifyItem(downloadItem); notifyQueueChanged(); startNextDownload()
    }

    private func makeTrackPart(downloadItemId: String, item: LibraryItem, track: AudioTrack, episode: PodcastEpisode?) throws -> DownloadItemPart {
        guard let filename = track.metadata?.filename else { throw LibraryItemDownloadError.noMetadata }
        let trackPath = track.metadata?.path ?? ""
        let ino: String
        if item.mediaType == "podcast" {
            ino = item.media?.episodes.first(where: { $0.audioFile?.metadata?.path == trackPath })?.audioFile?.ino ?? ""
        } else {
            ino = item.media?.audioFiles.first(where: { $0.metadata?.path == trackPath })?.ino ?? ""
        }
        let serverPath = "/api/items/\(item.id)/file/\(ino)/download"
        let relative = "\(try createLibraryItemFileDirectory(item: item))/\(filename)"
        return DownloadItemPart(downloadItemId: downloadItemId, filename: filename, destination: relative, itemTitle: track.title ?? "Unknown", serverPath: serverPath, audioTrack: track, episode: episode, ebookFile: nil, size: track.metadata?.size ?? 0)
    }

    private func makeEbookPart(downloadItemId: String, item: LibraryItem, ebookFile: EBookFile) throws -> DownloadItemPart {
        let filename = ebookFile.metadata?.filename ?? "ebook.\(ebookFile.ebookFormat)"
        let relative = "\(try createLibraryItemFileDirectory(item: item))/\(filename)"
        return DownloadItemPart(downloadItemId: downloadItemId, filename: filename, destination: relative, itemTitle: filename, serverPath: "/api/items/\(item.id)/file/\(ebookFile.ino)/download", audioTrack: nil, episode: nil, ebookFile: ebookFile, size: ebookFile.metadata?.size ?? 0)
    }

    private func makeCoverPart(downloadItemId: String, item: LibraryItem) throws -> DownloadItemPart {
        let relative = "\(try createLibraryItemFileDirectory(item: item))/cover.jpg"
        let coverFile = item.libraryFiles.first(where: { $0.metadata?.path == item.media?.coverPath })
        return DownloadItemPart(downloadItemId: downloadItemId, filename: "cover.jpg", destination: relative, itemTitle: "cover", serverPath: "/api/items/\(item.id)/cover", audioTrack: nil, episode: nil, ebookFile: nil, size: coverFile?.metadata?.size ?? 0)
    }

    private func createLibraryItemFileDirectory(item: LibraryItem) throws -> String {
        guard Self.itemDownloadFolder(path: item.id) != nil else { throw LibraryItemDownloadError.failedDirectory }
        return item.id
    }

    static func itemDownloadFolder(path: String) -> URL? {
        do {
            var folder = downloadsDirectory.appendingPathComponent(path)
            guard folder.standardizedFileURL.path.hasPrefix(downloadsDirectory.standardizedFileURL.path + "/") else { return nil }
            if !FileManager.default.fileExists(atPath: folder.path) { try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true) }
            var values = URLResourceValues(); values.isExcludedFromBackup = true; try folder.setResourceValues(values)
            return folder
        } catch {
            AbsLogger.error(message: "Failed to create download directory", error: error)
            return nil
        }
    }
}

private struct SpeedSample {
    let timestamp: TimeInterval
    let bytes: Double
}

enum LibraryItemDownloadError: LocalizedError {
    case noTracks
    case noMetadata
    case podcastEpisodeNotFound
    case unknownMediaType
    case failedDirectory
    case downloadItemPartNotFound
    case failedDownload(String)

    var errorDescription: String? {
        switch self {
        case .noTracks: return "No downloadable files"
        case .noMetadata: return "Download metadata is missing"
        case .podcastEpisodeNotFound: return "Podcast episode not found"
        case .unknownMediaType: return "Unsupported media type"
        case .failedDirectory: return "Could not create the download directory"
        case .downloadItemPartNotFound: return "Download state could not be restored"
        case .failedDownload(let message): return message
        }
    }
}
