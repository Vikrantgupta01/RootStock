package com.sinewlabs.vinnies.mcp.localservice;

import com.sinewlabs.vinnies.mcp.vocabulary.NeedCategory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocalServiceRepository extends JpaRepository<LocalService, UUID> {

	List<LocalService> findByNeedCategory(NeedCategory needCategory);
}
