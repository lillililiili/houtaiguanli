package com.uav.lowaltitude.modules.assessment.engine.checks;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleCheck;
@Configuration
public class ExecutionFactChecks {
    @Bean RuleCheck takeoffExecutionCheck(){return new ExecutionFactCheck("C02-9","takeoff_point","起飞点");}
    @Bean RuleCheck landingExecutionCheck(){return new ExecutionFactCheck("C02-10","landing_point","降落点");}
    @Bean RuleCheck pilotExecutionCheck(){return new ExecutionFactCheck("C02-11","pilot","执行飞手");}
    @Bean RuleCheck reportingExecutionCheck(){return new ExecutionFactCheck("C02-12","reporting_unit","报送单位");}
}
