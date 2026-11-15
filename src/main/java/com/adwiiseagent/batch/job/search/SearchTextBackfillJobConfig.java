package com.adwiiseagent.batch.job.search;

import com.adwiiseagent.batch.service.SearchTextBackfillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Manual/scheduled job: rebuild search_text (and optional OCR) for public search entities.
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class SearchTextBackfillJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final SearchTextBackfillService searchTextBackfillService;

    @Bean
    public Job searchTextBackfillJob() {
        return new JobBuilder("searchTextBackfillJob", jobRepository)
            .start(searchTextBackfillStep())
            .build();
    }

    @Bean
    public Step searchTextBackfillStep() {
        return new StepBuilder("searchTextBackfillStep", jobRepository)
            .tasklet(searchTextBackfillTasklet(), transactionManager)
            .build();
    }

    @Bean
    public Tasklet searchTextBackfillTasklet() {
        return (contribution, chunkContext) -> {
            log.info("[SearchTextBackfillJob] Starting");
            SearchTextBackfillService.BackfillResult result = searchTextBackfillService.runBackfill();
            contribution.incrementWriteCount(result.structuredUpdates() + result.ocrUpdates());
            log.info(
                "[SearchTextBackfillJob] Finished structured={} ocr={} errors={}",
                result.structuredUpdates(),
                result.ocrUpdates(),
                result.errors().size()
            );
            return RepeatStatus.FINISHED;
        };
    }
}
