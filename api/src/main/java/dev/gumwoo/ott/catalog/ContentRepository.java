package dev.gumwoo.ott.catalog;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface ContentRepository extends JpaRepository<Content, String> {

	List<Content> findByCurrentVersionIsNotNullOrderByTitleAsc();

	Optional<Content> findByIdAndCurrentVersionIsNotNull(String id);

}
