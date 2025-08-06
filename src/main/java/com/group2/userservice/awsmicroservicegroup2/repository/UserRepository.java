package com.group2.userservice.awsmicroservicegroup2.repository;

import com.group2.userservice.awsmicroservicegroup2.model.User;
import org.socialsignin.spring.data.dynamodb.repository.EnableScan;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;


@EnableScan
@Repository
public interface UserRepository extends PagingAndSortingRepository<User, String> {
}
