package top.itning.yunshunas.music.dto;

import lombok.Data;

import java.util.List;

/**
 * 分页结果
 *
 * @param <T> 条目类型
 * @author 78HUA
 */
@Data
public class PageResult<T> {
    /**
     * 当前页码，从 1 开始
     */
    private int page;
    /**
     * 每页条数
     */
    private int size;
    /**
     * 总条数
     */
    private long total;
    /**
     * 总页数
     */
    private int totalPages;
    /**
     * 当前页数据
     */
    private List<T> items;

    public static <T> PageResult<T> of(int page, int size, long total, List<T> items) {
        PageResult<T> result = new PageResult<>();
        result.setPage(page);
        result.setSize(size);
        result.setTotal(total);
        result.setTotalPages(size <= 0 ? 0 : (int) ((total + size - 1) / size));
        result.setItems(items);
        return result;
    }
}
