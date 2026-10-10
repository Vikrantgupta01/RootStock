package com.rootstock.core.cases;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Where case files are kept. */
public interface CaseStore {

	void save(CaseFile file);

	Optional<CaseFile> byId(String caseId);

	/** Cases that go on with a graph next (waiting for someone to start it), oldest first. */
	List<CaseFile> waiting();

	/** In memory, for tests and for running without a database. */
	static CaseStore inMemory() {
		Map<String, CaseFile> files = new ConcurrentHashMap<>();
		return new CaseStore() {

			@Override
			public void save(CaseFile file) {
				files.put(file.caseId(), file);
			}

			@Override
			public Optional<CaseFile> byId(String caseId) {
				return Optional.ofNullable(files.get(caseId));
			}

			@Override
			public List<CaseFile> waiting() {
				return files.values().stream().filter(f -> f.next() != null && !CaseFile.RUNNING.equals(f.status()))
						.sorted(Comparator.comparing(CaseFile::updatedAt)).toList();
			}
		};
	}
}
