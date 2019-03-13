package io.syndesis.server.controller.integration.camelk.customizer;

import io.fabric8.kubernetes.api.model.Secret;
import io.syndesis.common.model.integration.IntegrationDeployment;
import io.syndesis.common.model.integration.StepKind;
import io.syndesis.common.model.integration.step.template.TemplateStepLanguage;
import io.syndesis.server.controller.integration.camelk.crd.Integration;
import io.syndesis.server.controller.integration.camelk.crd.IntegrationSpec;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static io.syndesis.common.util.Optionals.asStream;

/**
 * Adds libraries needed for templating steps
 */
@Component
public class TemplatingCamelKIntegrationCustomizer implements CamelKIntegrationCustomizer {

    @Override
    public Integration customize(IntegrationDeployment deployment, Integration integration, Secret secret) {

        Set<TemplateStepLanguage> languages = deployment.getSpec().getFlows().stream()
            .flatMap(f -> f.getSteps().stream())
            .filter(s -> s.getStepKind() == StepKind.template)
            .flatMap(s -> asStream(Optional.of(s.getConfiguredProperties().get("language"))))
            .map(TemplateStepLanguage::stepLanguage)
            .collect(Collectors.toSet());

        if (!languages.isEmpty()) {
            IntegrationSpec.Builder spec = new IntegrationSpec.Builder();
            if (integration.getSpec() != null) {
                spec = spec.from(integration.getSpec());
            }

            for (TemplateStepLanguage lan : languages) {
                spec = spec.addDependencies("mvn:" + lan.mavenDependency());
            }

            integration.setSpec(spec.build());
        }
        return integration;
    }

}
