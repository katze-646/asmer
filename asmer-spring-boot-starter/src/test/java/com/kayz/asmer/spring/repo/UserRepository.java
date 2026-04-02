package com.kayz.asmer.spring.repo;

import com.kayz.asmer.spring.model.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface UserRepository extends JpaRepository<UserEntity, Long> {

    List<UserEntity> findByIdIn(Collection<Long> ids);
}
