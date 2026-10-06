package com.sinewlabs.vinnies.mcp.assistance;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssistanceRepository extends JpaRepository<Assistance, UUID> {
}
