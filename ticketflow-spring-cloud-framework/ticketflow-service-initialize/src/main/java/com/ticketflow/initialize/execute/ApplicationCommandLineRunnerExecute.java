package com.ticketflow.initialize.execute;

import com.ticketflow.initialize.execute.base.AbstractApplicationExecute;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ConfigurableApplicationContext;

import static com.ticketflow.initialize.constant.InitializeHandlerType.APPLICATION_COMMAND_LINE_RUNNER;

/**
 * {@link CommandLineRunner} 初始化执行器。在Spring应用启动后执行命令行参数相关的初始化逻辑。
 **/
public class ApplicationCommandLineRunnerExecute extends AbstractApplicationExecute implements CommandLineRunner {
    
    public ApplicationCommandLineRunnerExecute(ConfigurableApplicationContext applicationContext){
        super(applicationContext);
    }
    
    @Override
    public void run(final String... args) {
        execute();
    }
    
    @Override
    public String type() {
        return APPLICATION_COMMAND_LINE_RUNNER;
    }
}
