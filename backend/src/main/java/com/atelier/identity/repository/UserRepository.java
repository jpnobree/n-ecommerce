package com.atelier.identity.repository;

import com.atelier.identity.domain.AppUser;
import com.atelier.identity.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface UserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByCpfAndIdNot(String cpf, Long id);

    @Query("select count(u) > 0 from AppUser u join u.roles r where r = :role")
    boolean existsWithRole(Role role);

    @Query("select u.tokenVersion from AppUser u where u.id = :id and u.status = com.atelier.identity.domain.UserStatus.ACTIVE")
    Optional<Integer> findActiveTokenVersion(Long id);
}
