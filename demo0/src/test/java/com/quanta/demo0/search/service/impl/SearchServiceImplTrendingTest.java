package com.quanta.demo0.search.service.impl;

import com.quanta.demo0.search.service.TrendingCacheService;
import com.quanta.demo0.search.vo.SearchTrendingVO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchServiceImplTrendingTest {

    @Test
    void returnsCachedValueWithoutInvokingLoader() {
        SearchTrendingVO cached = trending("cached");
        CountingLoader loader = new CountingLoader(trending("database"), null);
        FixedCache cache = new FixedCache(cached, false);
        SearchServiceImpl service = service(cache, loader);

        SearchTrendingVO actual = service.getTrending();

        assertThat(actual).isSameAs(cached);
        assertThat(loader.loads).hasValue(0);
        assertThat(cache.receivedLoader).isNotNull();
    }

    @Test
    void cacheCanInvokeTheProvidedTrendingLoader() {
        SearchTrendingVO database = trending("database");
        CountingLoader loader = new CountingLoader(database, null);
        FixedCache cache = new FixedCache(null, true);
        SearchServiceImpl service = service(cache, loader);

        SearchTrendingVO actual = service.getTrending();

        assertThat(actual).isSameAs(database);
        assertThat(loader.loads).hasValue(1);
    }

    @Test
    void sourceFailureIsNotConvertedToEmptySuccess() {
        IllegalStateException failure = new IllegalStateException("mysql unavailable");
        CountingLoader loader = new CountingLoader(null, failure);
        FixedCache cache = new FixedCache(null, true);
        SearchServiceImpl service = service(cache, loader);

        assertThatThrownBy(service::getTrending).isSameAs(failure);
    }

    private SearchServiceImpl service(
            TrendingCacheService cache,
            TrendingDataLoader loader
    ) {
        SearchServiceImpl service = new SearchServiceImpl();
        ReflectionTestUtils.setField(service, "trendingCacheService", cache);
        ReflectionTestUtils.setField(service, "trendingDataLoader", loader);
        return service;
    }

    private SearchTrendingVO trending(String keyword) {
        return SearchTrendingVO.builder()
                .hotKeywords(List.of(keyword))
                .hotQuestions(List.of())
                .hotAlumni(List.of())
                .build();
    }

    private static final class FixedCache implements TrendingCacheService {

        private final SearchTrendingVO fixedValue;
        private final boolean invokeLoader;
        private Supplier<SearchTrendingVO> receivedLoader;

        private FixedCache(SearchTrendingVO fixedValue, boolean invokeLoader) {
            this.fixedValue = fixedValue;
            this.invokeLoader = invokeLoader;
        }

        @Override
        public SearchTrendingVO getOrLoad(Supplier<SearchTrendingVO> loader) {
            receivedLoader = loader;
            return invokeLoader ? loader.get() : fixedValue;
        }

        @Override
        public void evict() {
        }
    }

    private static final class CountingLoader extends TrendingDataLoader {

        private final SearchTrendingVO value;
        private final RuntimeException failure;
        private final AtomicInteger loads = new AtomicInteger();

        private CountingLoader(SearchTrendingVO value, RuntimeException failure) {
            super(null, null, null, null, null);
            this.value = value;
            this.failure = failure;
        }

        @Override
        public SearchTrendingVO load() {
            loads.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return value;
        }
    }
}
