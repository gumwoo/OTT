package dev.gumwoo.ott.catalog;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ContentRepository extends JpaRepository<Content, String> {

	List<Content> findByCurrentVersionIsNotNullOrderByTitleAsc();

	Optional<Content> findByIdAndCurrentVersionIsNotNull(String id);

	// save()는 ID가 정해진 엔티티를 merge해서 기존 행을 덮어쓴다
	@Modifying
	@Query(value = "insert into contents (id, title, description, required_plan, duration_sec) "
			+ "values (:id, :title, :description, :requiredPlan, :durationSec) on conflict (id) do nothing",
			nativeQuery = true)
	int insertIfAbsent(@Param("id") String id, @Param("title") String title, @Param("description") String description,
			@Param("requiredPlan") String requiredPlan, @Param("durationSec") int durationSec);

}
