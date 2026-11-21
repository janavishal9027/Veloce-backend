package com.veloce.auth;

import jakarta.persistence.*;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

@Entity
@Table(name = "accounts")
class Account {
    @Id UUID id;
    @Column(nullable=false, length=254) String email;
    @Column(name="display_name", nullable=false, length=80) String displayName;
    @Column(name="password_hash", nullable=false, length=100) String passwordHash;
    protected Account() {}
    Account(UUID id, String email, String name, String hash) {
        this.id=id; this.email=email; this.displayName=name; this.passwordHash=hash;
    }
}

interface Accounts extends JpaRepository<Account, UUID> {
    Optional<Account> findByEmail(String email);
}
