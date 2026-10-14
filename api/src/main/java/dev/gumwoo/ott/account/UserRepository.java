package dev.gumwoo.ott.account;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface UserRepository extends JpaRepository<User, Long> {

	Optional<User> findByEmail(String email);

	boolean existsByEmail(String email);

	// 여러 Pod가 동시에 떠도 한 번만 만들어지게 (app-design.md §5)
	@Modifying
	@Query(value = "insert into users (email, password_hash, role) values (:email, :passwordHash, 'ADMIN') "
			+ "on conflict (email) do nothing", nativeQuery = true)
	int insertAdminIfAbsent(@Param("email") String email, @Param("passwordHash") String passwordHash);

}
