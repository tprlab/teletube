CREATE TABLE IF NOT EXISTS `ChannelRecord` (`id` INTEGER NOT NULL, `title` TEXT NOT NULL, `available` INTEGER NOT NULL, `archived` INTEGER NOT NULL, PRIMARY KEY(`id`));
CREATE TABLE IF NOT EXISTS `ScanSettings` (`key` INTEGER NOT NULL, `allChannels` INTEGER NOT NULL, PRIMARY KEY(`key`));
CREATE TABLE IF NOT EXISTS `SelectedChannel` (`channelId` INTEGER NOT NULL, PRIMARY KEY(`channelId`));
CREATE TABLE IF NOT EXISTS `ScanProgress` (`channelId` INTEGER NOT NULL, `newest` INTEGER NOT NULL, `pendingHead` INTEGER NOT NULL, `pendingCursor` INTEGER NOT NULL, `initialCutoff` INTEGER, `olderCursor` INTEGER NOT NULL, `historyComplete` INTEGER NOT NULL, `lastSynced` INTEGER NOT NULL, PRIMARY KEY(`channelId`));
CREATE TABLE IF NOT EXISTS `VideoPost` (`channelId` INTEGER NOT NULL, `messageId` INTEGER NOT NULL, `videoId` TEXT NOT NULL, `postedAt` INTEGER NOT NULL, `url` TEXT NOT NULL, `title` TEXT NOT NULL, PRIMARY KEY(`channelId`, `messageId`, `videoId`));
CREATE INDEX IF NOT EXISTS `index_VideoPost_postedAt` ON `VideoPost` (`postedAt`);
CREATE INDEX IF NOT EXISTS `index_VideoPost_channelId_postedAt` ON `VideoPost` (`channelId`, `postedAt`);
CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT);
INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '2fa13a763c5f902ee21fb044f38451b1');

INSERT INTO ChannelRecord VALUES (1, 'First channel', 1, 0);
INSERT INTO ChannelRecord VALUES (2, 'Second channel', 1, 1);
INSERT INTO ScanSettings VALUES (0, 0);
INSERT INTO SelectedChannel VALUES (1), (2);
INSERT INTO ScanProgress VALUES (1, 10, 0, 0, NULL, 10, 0, 150);
INSERT INTO VideoPost VALUES (1, 10, 'dQw4w9WgXcQ', 100, 'https://youtu.be/dQw4w9WgXcQ', 'First');
INSERT INTO VideoPost VALUES (2, 20, 'aqz-KE-bpKQ', 200, 'https://youtu.be/aqz-KE-bpKQ', 'Second');
