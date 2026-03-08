package com.stzteam.features.marsprocessor;

import java.lang.annotation.Repeatable;

@Repeatable(CreateCommands.class)
public @interface CreateCommand {
    String name();
    String[] exclude() default {};
}
