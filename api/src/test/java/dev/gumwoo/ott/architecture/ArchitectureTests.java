package dev.gumwoo.ott.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import dev.gumwoo.ott.common.error.ApiException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(packages = "dev.gumwoo.ott", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTests {

	private static final Pattern FEATURE = Pattern.compile("dev\\.gumwoo\\.ott\\.([^.]+)(\\.([^.]+))?.*");

	private static final DescribedPredicate<JavaClass> TRANSACTIONAL = DescribedPredicate.describe("transactional",
			(JavaClass c) -> c.isAnnotatedWith(Transactional.class)
					|| c.getMethods().stream().anyMatch((m) -> m.isAnnotatedWith(Transactional.class)));

	// SP-02
	@ArchTest
	static final ArchRule webDoesNotUseRepositories = noClasses().that()
		.resideInAPackage("..web..")
		.should()
		.dependOnClassesThat()
		.areAssignableTo(Repository.class);

	// SP-03
	@ArchTest
	static final ArchRule featuresAreFreeOfCycles = slices().matching("dev.gumwoo.ott.(*)..").should().beFreeOfCycles();

	// SP-03
	@ArchTest
	static final ArchRule noAccessToOtherFeaturesWebOrInfra = classes().that()
		.resideInAPackage("dev.gumwoo.ott..")
		.should(notDependOnOtherFeaturesWebOrInfra());

	// SP-04
	@ArchTest
	static final ArchRule constructorInjectionOnly = NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

	// SP-05
	@ArchTest
	static final ArchRule runtimeExceptionsExtendApiException = classes().that()
		.areAssignableTo(RuntimeException.class)
		.should()
		.beAssignableTo(ApiException.class);

	// SP-07
	@ArchTest
	static final ArchRule transactionalClassesDoNotTouchInfra = noClasses().that(TRANSACTIONAL)
		.should()
		.dependOnClassesThat()
		.resideInAPackage("..infra..");

	// SP-08
	@ArchTest
	static final ArchRule noTransactionalInWeb = noClasses().that()
		.resideInAPackage("..web..")
		.should(beTransactional());

	private static ArchCondition<JavaClass> beTransactional() {
		return new ArchCondition<>("be @Transactional") {
			@Override
			public void check(JavaClass item, ConditionEvents events) {
				events.add(new SimpleConditionEvent(item, TRANSACTIONAL.test(item),
						item.getName() + " uses @Transactional"));
			}
		};
	}

	private static ArchCondition<JavaClass> notDependOnOtherFeaturesWebOrInfra() {
		return new ArchCondition<>("not depend on web or infra of other features") {
			@Override
			public void check(JavaClass item, ConditionEvents events) {
				String own = feature(item.getPackageName());
				for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
					Matcher target = FEATURE.matcher(dependency.getTargetClass().getPackageName());
					if (!target.matches() || target.group(1).equals(own)) {
						continue;
					}
					String layer = target.group(3);
					if ("web".equals(layer) || "infra".equals(layer)) {
						events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
					}
				}
			}
		};
	}

	private static String feature(String packageName) {
		Matcher matcher = FEATURE.matcher(packageName);
		return matcher.matches() ? matcher.group(1) : "";
	}

}
