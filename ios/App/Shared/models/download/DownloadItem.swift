//
//  DownloadItem.swift
//  App
//
//  Created by Ron Heft on 8/16/22.
//

import Foundation
import RealmSwift

class DownloadItem: Object, Codable {
    @Persisted(primaryKey: true) var id:String?
    @Persisted(indexed: true) var libraryItemId: String?
    @Persisted var episodeId: String?
    @Persisted var userMediaProgress: MediaProgress?
    @Persisted var serverConnectionConfigId: String?
    @Persisted var serverAddress: String?
    @Persisted var serverUserId: String?
    @Persisted var mediaType: String?
    @Persisted var itemTitle: String?
    @Persisted var media: MediaType?
    @Persisted var downloadItemParts = List<DownloadItemPart>()
    @Persisted var allowCellularDownload: Bool = false
    @Persisted var isPaused: Bool = false
    @Persisted var queuePosition: Int = 0
    @Persisted var terminalFailureAt: Double?
    
    private enum CodingKeys : String, CodingKey {
        case id, libraryItemId, episodeId, serverConnectionConfigId, serverAddress, serverUserId, mediaType, itemTitle, downloadItemParts
        case allowCellularDownload, isPaused, queuePosition
    }
    
    override init() {
        super.init()
    }
    
    required init(from decoder: Decoder) throws {
        super.init()
        
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try? values.decode(String.self, forKey: .id)
        libraryItemId = try? values.decode(String.self, forKey: .libraryItemId)
        episodeId = try? values.decode(String.self, forKey: .episodeId)
        serverConnectionConfigId = try? values.decode(String.self, forKey: .serverConnectionConfigId)
        serverAddress = try? values.decode(String.self, forKey: .serverAddress)
        serverUserId = try? values.decode(String.self, forKey: .serverUserId)
        mediaType = try? values.decode(String.self, forKey: .mediaType)
        itemTitle = try? values.decode(String.self, forKey: .itemTitle)
        allowCellularDownload = (try? values.decode(Bool.self, forKey: .allowCellularDownload)) ?? false
        isPaused = (try? values.decode(Bool.self, forKey: .isPaused)) ?? false
        queuePosition = (try? values.decode(Int.self, forKey: .queuePosition)) ?? 0
        if let parts = try? values.decode([DownloadItemPart].self, forKey: .downloadItemParts) {
            downloadItemParts.append(objectsIn: parts)
        }
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(libraryItemId, forKey: .libraryItemId)
        try container.encode(episodeId, forKey: .episodeId)
        try container.encode(serverConnectionConfigId, forKey: .serverConnectionConfigId)
        try container.encode(serverAddress, forKey: .serverAddress)
        try container.encode(serverUserId, forKey: .serverUserId)
        try container.encode(mediaType, forKey: .mediaType)
        try container.encode(itemTitle, forKey: .itemTitle)
        try container.encode(Array(downloadItemParts), forKey: .downloadItemParts)
        try container.encode(allowCellularDownload, forKey: .allowCellularDownload)
        try container.encode(isPaused, forKey: .isPaused)
        try container.encode(queuePosition, forKey: .queuePosition)
    }
}

extension DownloadItem {
    convenience init(libraryItem: LibraryItem, episodeId: String?, server: ServerConnectionConfig) {
        self.init()
        
        self.id = libraryItem.id
        self.libraryItemId = libraryItem.id
        self.userMediaProgress = libraryItem.userMediaProgress
        self.serverConnectionConfigId = server.id
        self.serverAddress = server.address
        self.serverUserId = server.userId
        self.mediaType = libraryItem.mediaType
        self.itemTitle = libraryItem.media?.metadata?.title
        self.media = libraryItem.media
        
        if let episodeId = episodeId {
            self.id! += "-\(episodeId)"
            self.episodeId = episodeId
        }
    }
    
    func isDoneDownloading() -> Bool {
        self.downloadItemParts.allSatisfy({ $0.completed })
    }
    
    func didDownloadSuccessfully() -> Bool {
        self.downloadItemParts.allSatisfy({ $0.failed == false })
    }
    
    func delete() throws {
        try self.realm?.write {
            self.realm?.delete(self.downloadItemParts)
            self.realm?.delete(self)
        }
    }
}
