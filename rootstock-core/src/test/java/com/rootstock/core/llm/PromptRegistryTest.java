package com.rootstock.core.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PromptRegistryTest {

	private static final PromptTemplate BUNDLED = new PromptTemplate("pack/extract", null, null,
			PromptTemplate.Source.BUNDLED, List.of(new PromptTemplate.Part("user", "bundled {{notes}}")));

	private final MutableClock clock = new MutableClock();
	private final AtomicInteger fetches = new AtomicInteger();

	private PromptSource langfuse(int version) {
		return (name, label) -> {
			fetches.incrementAndGet();
			return Optional.of(new PromptTemplate(name, label, version, PromptTemplate.Source.LANGFUSE,
					List.of(new PromptTemplate.Part("user", "v" + version))));
		};
	}

	private static final PromptSource UNREACHABLE = (name, label) -> {
		throw new IllegalStateException("connection refused");
	};

	private PromptRegistry registry(PromptSource source) {
		PromptRegistry registry = new PromptRegistry(source, Duration.ofMinutes(5), clock);
		registry.addBundled(Map.of(BUNDLED.name(), BUNDLED));
		return registry;
	}

	@Test
	void fetchesByNameAndLabelAndCachesForTheTtl() {
		PromptRegistry registry = registry(langfuse(3));

		PromptTemplate first = registry.get("pack/extract", "staging");
		registry.get("pack/extract", "staging");

		assertThat(first.version()).isEqualTo(3);
		assertThat(first.label()).isEqualTo("staging");
		assertThat(first.source()).isEqualTo(PromptTemplate.Source.LANGFUSE);
		assertThat(fetches).hasValue(1);

		clock.advance(Duration.ofMinutes(6));
		registry.get("pack/extract", "staging");
		assertThat(fetches).hasValue(2);
	}

	@Test
	void theLabelDefaultsToProduction() {
		assertThat(registry(langfuse(1)).get("pack/extract", null).label()).isEqualTo("production");
	}

	@Test
	void unreachableLangfuseUsesTheLastCopyThenTheBundledOne() {
		AtomicInteger calls = new AtomicInteger();
		PromptSource flaky = (name, label) -> {
			if (calls.incrementAndGet() == 1) {
				return langfuse(7).fetch(name, label);
			}
			throw new IllegalStateException("connection refused");
		};
		PromptRegistry registry = registry(flaky);
		registry.get("pack/extract", "production");
		clock.advance(Duration.ofMinutes(6));

		assertThat(registry.get("pack/extract", "production").version()).isEqualTo(7);
		assertThat(registry(UNREACHABLE).get("pack/extract", "production").source())
				.isEqualTo(PromptTemplate.Source.BUNDLED);
	}

	@Test
	void aPromptLangfuseDoesNotHaveFallsBackToTheBundledCopy() {
		PromptRegistry registry = registry((name, label) -> Optional.empty());

		PromptTemplate prompt = registry.get("pack/extract", "production");

		assertThat(prompt.source()).isEqualTo(PromptTemplate.Source.BUNDLED);
		assertThat(prompt.version()).isNull();
		assertThat(prompt.describe()).isEqualTo("pack/extract (bundled)");
	}

	@Test
	void withoutLangfuseOnlyBundledCopiesAreUsed() {
		assertThat(new PromptRegistry(null, Duration.ofMinutes(5), clock).hasBundled("pack/extract")).isFalse();
		assertThat(registry(null).get("pack/extract", "production").source()).isEqualTo(PromptTemplate.Source.BUNDLED);
	}

	@Test
	void aPromptNoOneHasIsAnError() {
		assertThatThrownBy(() -> registry(UNREACHABLE).get("pack/nope", "production"))
				.isInstanceOf(PromptNotFoundException.class).hasMessageContaining("pack/nope");
	}

	@Test
	void twoPacksCannotBundleTheSamePrompt() {
		PromptRegistry registry = registry(null);

		assertThatThrownBy(() -> registry.addBundled(Map.of(BUNDLED.name(), BUNDLED)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void renderFillsEveryPlaceholderAndRefusesMissingOnes() {
		PromptTemplate t = new PromptTemplate("p", "production", 1, PromptTemplate.Source.LANGFUSE,
				List.of(new PromptTemplate.Part("system", "Schema: {{ schema }}"),
						new PromptTemplate.Part("user", "Notes: {{notes}} ($5 owing)")));

		assertThat(t.variables()).containsExactly("notes", "schema");
		assertThat(t.render(Map.of("schema", "{}", "notes", "cost $3 \\ week"))).containsExactly(
				new PromptTemplate.Part("system", "Schema: {}"),
				new PromptTemplate.Part("user", "Notes: cost $3 \\ week ($5 owing)"));
		assertThatThrownBy(() -> t.render(Map.of("schema", "{}"))).hasMessageContaining("[notes]");
	}

	@Test
	void langfuseChatAndTextPromptsAreRead() {
		PromptTemplate chat = LangfusePromptSource.parse("""
				{"name":"pack/extract","version":4,"type":"chat","labels":["production"],
				 "prompt":[{"role":"system","content":"S"},{"type":"placeholder","name":"history"},
				           {"role":"user","content":"U {{notes}}"}]}""", "production");
		PromptTemplate text = LangfusePromptSource.parse("""
				{"name":"pack/judge","version":1,"type":"text","prompt":"Judge {{record}}"}""", "staging");

		assertThat(chat.messages()).containsExactly(new PromptTemplate.Part("system", "S"),
				new PromptTemplate.Part("user", "U {{notes}}"));
		assertThat(chat.version()).isEqualTo(4);
		assertThat(chat.describe()).isEqualTo("pack/extract v4 (langfuse)");
		assertThat(text.messages()).containsExactly(new PromptTemplate.Part("user", "Judge {{record}}"));
		assertThat(text.label()).isEqualTo("staging");
	}

	@Test
	void bundledPromptsAreReadFromAPacksPromptsFolder() throws Exception {
		Path pack = Path.of(getClass().getResource("/packs/repairs").toURI());

		Map<String, PromptTemplate> bundled = BundledPrompts.load(pack);

		assertThat(bundled).containsOnlyKeys("repairs/extract-job", "repairs/judge", "repairs/lookup", "repairs/plan-visit");
		PromptTemplate t = bundled.get("repairs/extract-job");
		assertThat(t.messages()).extracting(PromptTemplate.Part::role).containsExactly("system", "user");
		assertThat(t.variables()).containsExactly("glossary", "report", "schema", "today");
		assertThat(BundledPrompts.load(pack.resolve("agents"))).isEmpty();
	}

	private static final class MutableClock extends Clock {

		private Instant now = Instant.parse("2026-10-10T00:00:00Z");

		void advance(Duration d) {
			now = now.plus(d);
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public java.time.ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}
	}
}
