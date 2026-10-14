package com.adwiiseagent.batch.repository;

import com.adwiiseagent.batch.domain.GalleryCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GalleryCategoryRepository extends JpaRepository<GalleryCategory, Long> {

    List<GalleryCategory> findByTenantIdAndIsActiveTrueOrderBySortOrderAsc(String tenantId);
}
