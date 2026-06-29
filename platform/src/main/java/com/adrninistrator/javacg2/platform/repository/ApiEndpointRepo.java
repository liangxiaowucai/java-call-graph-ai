package com.adrninistrator.javacg2.platform.repository;

import com.adrninistrator.javacg2.platform.entity.ApiEndpointEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ApiEndpointRepo extends JpaRepository<ApiEndpointEntity, Long> {
    List<ApiEndpointEntity> findByRepoId(Long repoId);
    List<ApiEndpointEntity> findByRepoIdAndEndpointType(Long repoId, String endpointType);
    Optional<ApiEndpointEntity> findByRepoIdAndUrlPath(Long repoId, String urlPath);
    Optional<ApiEndpointEntity> findFirstByRepoIdAndFullMethod(Long repoId, String fullMethod);
    List<ApiEndpointEntity> findByFullMethod(String fullMethod);
    void deleteByRepoId(Long repoId);
}
