-- ============================================================
-- 云舒NAS · music 表索引迁移
--
-- 为什么要手工执行？
--   建表 DDL 写在程序里（nas-common/.../db/DbEntry.java），
--   由 HikariCP 的 connectionInitSql 在每条新连接上执行，
--   且语句是 CREATE TABLE IF NOT EXISTS —— **已存在的库不会被更新**。
--   所以索引变更对存量库必须手工迁移；新库由改过的 DDL 直接建好。
--
-- 执行方式：
--   mysql -h 127.0.0.1 -u root -p yunshu_nas < music-index-migration.sql
--
-- 说明：MySQL 8 不支持 DROP INDEX IF EXISTS，重复执行会报索引不存在，
--       属预期现象，可忽略。
-- ============================================================

USE `yunshu_nas`;

-- ------------------------------------------------------------
-- 1. 删除冗余索引 index_music_id
--    它与 UK_music_id 建在同一列 music_id 上，且都是普通 BTREE，功能完全重复：
--    唯一性已由 UK_music_id 保证，这个索引只会增加每次写入的维护成本与磁盘占用。
-- ------------------------------------------------------------
ALTER TABLE `music` DROP INDEX `index_music_id`;

-- ------------------------------------------------------------
-- 2. 新增 (name, singer, type) 联合索引
--    对应 MusicRepositoryImpl.findByNameAndSingerAndType：
--      SELECT * FROM music WHERE name = ? AND singer = ? AND type = ?
--    三个条件全是等值查询，但原先一个索引都没有 —— 优化器只能全表扫描。
--    实测 5 万行下：扫 49928 行、filtered 仅 0.10%，约 58ms 只返回 2 行。
--    列顺序按「选择率从高到低」排：name 区分度最高（5000 个不同值），
--    其次是 singer（200 个），type 只有 4 个。三个条件都是等值，
--    因此该顺序对这条查询都能用上；若将来出现只按 name 查的场景，也符合最左前缀。
-- ------------------------------------------------------------
ALTER TABLE `music` ADD INDEX `idx_name_singer_type` (`name`, `singer`, `type`);
