package org.example.service;

import org.example.entity.User;

public interface UserService {
    User register(User user);
    User login(String username, String password);
    User findByUsername(String username);
}