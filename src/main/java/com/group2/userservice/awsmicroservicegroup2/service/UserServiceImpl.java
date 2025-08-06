package com.group2.userservice.awsmicroservicegroup2.service;

import com.group2.userservice.awsmicroservicegroup2.repository.UserRepository;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;

    public UserServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }


}
