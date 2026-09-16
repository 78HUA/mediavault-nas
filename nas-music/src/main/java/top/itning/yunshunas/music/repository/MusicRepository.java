package top.itning.yunshunas.music.repository;

import top.itning.yunshunas.music.entity.Music;

import java.util.List;
import java.util.Optional;

/**
 * @author itning
 * @since 2020/9/5 11:15
 */
public interface MusicRepository {
    Music save(Music music);

    boolean deleteById(Long id);

    Music update(Music music);

    List<Music> findAll();

    /**
     * 分页查询（按 gmt_create 倒序）
     *
     * @param offset 偏移量
     * @param limit  每页条数
     * @return 当前页数据
     */
    List<Music> findPage(long offset, int limit);

    /**
     * 统计总条数
     *
     * @return 总条数
     */
    long countAll();

    List<Music> findAllByNameLikeOrSingerLike(String name, String singer);

    List<Music> findAllByNameLike(String name);

    List<Music> findAllBySingerLike(String singer);

    Optional<Music> findByMusicId(String musicId);

    Optional<Music> findById(Long id);

    Optional<Music> findByNameAndSingerAndType(String name, String singer, Integer type);
}
