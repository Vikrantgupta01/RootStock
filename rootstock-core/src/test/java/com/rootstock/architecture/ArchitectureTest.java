package com.rootstock.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The package rules in CLAUDE.md, enforced. Dependencies point inwards only
 * (runtime -> autoconfig -> core), and nothing in Rootstock knows about any
 * domain: a new client is a new pack and connector, with no change here.
 */
@AnalyzeClasses(packages = "com.rootstock", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

	@ArchTest
	static final ArchRule coreDependsOnNeitherAutoconfigNorRuntime = noClasses()
			.that().resideInAPackage("com.rootstock.core..")
			.should().dependOnClassesThat().resideInAnyPackage("com.rootstock.autoconfig..", "com.rootstock.runtime..")
			.because("core holds the domain and must not know how it is wired or served");

	@ArchTest
	static final ArchRule autoconfigDoesNotDependOnRuntime = noClasses()
			.that().resideInAPackage("com.rootstock.autoconfig..")
			.should().dependOnClassesThat().resideInAPackage("com.rootstock.runtime..")
			.because("dependencies point inwards: runtime -> autoconfig -> core");

	@ArchTest
	static final ArchRule nothingDependsOnADomainPackage = noClasses()
			.that().resideInAPackage("com.rootstock..")
			.should().dependOnClassesThat().resideInAnyPackage("..vinnies..")
			.because("Rootstock is domain-agnostic: domains live in their own packs and connectors");

	@ArchTest
	static final ArchRule everyClassIsInALayer = classes()
			.that().resideInAPackage("com.rootstock..")
			.and().doNotHaveSimpleName("RootStockApplication")
			.should().resideInAnyPackage("com.rootstock.core..", "com.rootstock.autoconfig..", "com.rootstock.runtime..")
			.because("only the main class sits at the root, so component scanning covers all three layers");

	/**
	 * Names in strings, comments and resources escape the class rules above
	 * (a pointcut, a config key, a prompt), so the source text is checked too.
	 */
	@org.junit.jupiter.api.Test
	void noSourceOrResourceMentionsVinnies() throws IOException {
		List<Path> offenders;
		try (Stream<Path> files = Files.walk(Path.of("src/main"))) {
			offenders = files.filter(Files::isRegularFile)
					.filter(f -> read(f).toLowerCase(Locale.ROOT).contains("vinnies"))
					.toList();
		}
		assertThat(offenders).as("files in src/main mentioning Vinnies").isEmpty();
	}

	private static String read(Path file) {
		try {
			return Files.readString(file);
		}
		catch (IOException | java.io.UncheckedIOException ex) {
			return "";   // binary resource: not text, cannot name anything
		}
	}
}
