package com.sinewlabs.vinnies.mcp.localservice;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocalServiceRepository extends JpaRepository<LocalService, UUID> {
}
