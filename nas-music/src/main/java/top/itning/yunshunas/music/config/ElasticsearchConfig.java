package top.itning.yunshunas.music.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.util.StringUtils;
import top.itning.yunshunas.common.db.ApplicationConfig;
import top.itning.yunshunas.common.event.ConfigChangeEvent;
import top.itning.yunshunas.music.entity.Lyric;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

/**
 * @author itning
 * @since 2023/4/9 14:56
 */
@Slf4j
@Configuration
public class ElasticsearchConfig implements ApplicationListener<ConfigChangeEvent> {

    private final ApplicationConfig applicationConfig;

    private ElasticsearchTemplate elasticsearchTemplate;
    private RestClient restClient;
    private RestClientTransport restClientTransport;

    /**
     * 上次初始化失败的原因；null 表示本次初始化没失败过
     */
    private String lastError;

    @Autowired
    public ElasticsearchConfig(ApplicationConfig applicationConfig) {
        this.applicationConfig = applicationConfig;
    }

    @PostConstruct
    public void init() {
        lastError = null;
        ElasticsearchProperties properties = applicationConfig.getSetting(ElasticsearchProperties.class);
        if (Objects.isNull(properties) || !properties.isEnabled()) {
            return;
        }
        HttpHost[] hosts = properties.getUris().stream().map(this::createHttpHost).toArray(HttpHost[]::new);
        RestClientBuilder builder = RestClient.builder(hosts);
        if (properties.getPathPrefix() != null) {
            builder.setPathPrefix(properties.getPathPrefix());
        }
        restClient = builder.build();
        restClientTransport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        ElasticsearchClient elasticsearchClient = new ElasticsearchClient(restClientTransport);
        elasticsearchTemplate = new ElasticsearchTemplate(elasticsearchClient);
        // 建索引会真的发一次网络请求，这是「配了 ES 但连不上」时唯一会立刻暴露的地方。
        // 绝不能让失败穿出 @PostConstruct：那会让 Spring 取消整个刷新、应用直接起不来，
        // 而设置界面本身又由这个应用提供 —— 用户将无法从界面把配置改回去，形成死锁。
        try {
            IndexOperations indexOperations = elasticsearchTemplate.indexOps(Lyric.class);
            if (!indexOperations.exists()) {
                indexOperations.create();
            }
        } catch (Exception e) {
            lastError = e.getMessage();
            log.warn("Elasticsearch 配置已保存但当前不可用，本次降级为不启用：{}", lastError);
            // 降级必须彻底：把模板也清掉，enabled() 才会是 false，
            // 各调用点（删歌、上传歌词）才会走 no-op 分支 —— 否则每次调用都会抛异常，
            // 把一个「可选中间件」的问题变成「核心业务不能用」。
            destroy();
            return;
        }
        log.info("Elasticsearch 已启用：{}", properties.getUris());
    }

    @PreDestroy
    public void destroy() {
        if (Objects.nonNull(restClientTransport)) {
            try {
                restClientTransport.close();
            } catch (IOException ignore) {
            }
        }
        if (Objects.nonNull(restClient)) {
            try {
                restClient.close();
            } catch (IOException ignore) {
            }
        }
        elasticsearchTemplate = null;
    }

    @Override
    public void onApplicationEvent(ConfigChangeEvent event) {
        if (event.getSource() instanceof ElasticsearchProperties) {
            this.destroy();
            this.init();
        }
    }

    public ElasticsearchTemplate getElasticsearchTemplate() {
        if (Objects.isNull(elasticsearchTemplate)) {
            throw new RuntimeException("Elasticsearch未配置，请先配置！");
        }
        return elasticsearchTemplate;
    }

    public boolean enabled() {
        return Objects.nonNull(elasticsearchTemplate);
    }

    /**
     * 上次初始化失败的原因
     * <p>
     * 供设置接口把「配置存下来了，但当前连不上」如实回报给用户 ——
     * 只写日志的话用户看不到，会误以为 Elasticsearch 已经在用了。
     *
     * @return 失败原因；本次初始化没失败过则为 null
     */
    public String getLastError() {
        return lastError;
    }

    private HttpHost createHttpHost(String uri) {
        try {
            return createHttpHost(URI.create(uri));
        } catch (IllegalArgumentException ex) {
            return HttpHost.create(uri);
        }
    }

    private HttpHost createHttpHost(URI uri) {
        if (!StringUtils.hasLength(uri.getUserInfo())) {
            return HttpHost.create(uri.toString());
        }
        try {
            return HttpHost.create(new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(),
                    uri.getQuery(), uri.getFragment())
                    .toString());
        } catch (URISyntaxException ex) {
            throw new IllegalStateException(ex);
        }
    }

}
