package com.sinewlabs.vinnies.mcp.guideline;

import com.sinewlabs.vinnies.mcp.vocabulary.NeedCategory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssistanceGuidelineRepository extends JpaRepository<AssistanceGuideline, NeedCategory> {

	List<AssistanceGuideline> findAllByOrderByAssistanceTypeAsc();
}
