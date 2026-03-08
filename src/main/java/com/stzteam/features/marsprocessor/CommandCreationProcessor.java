package com.stzteam.features.marsprocessor;

import com.google.auto.service.AutoService;
import com.squareup.javapoet.*;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import java.io.IOException;
import java.util.*;

@AutoService(Processor.class)
@SupportedAnnotationTypes({
    "com.stzteam.features.marsprocessor.CreateCommand",
    "com.stzteam.features.marsprocessor.CreateCommands"
})
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class CommandCreationProcessor extends AbstractProcessor {

    // Clase auxiliar para guardar la información antes de generar el código
    private static class CommandInfo {
        CreateCommand annotation;
        TypeElement builderClass;

        CommandInfo(CreateCommand annotation, TypeElement builderClass) {
            this.annotation = annotation;
            this.builderClass = builderClass;
        }
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        // Agrupamos las clases por su Interfaz Padre (ej. setAngle pertenece a IntakeRequest)
        Map<TypeElement, List<CommandInfo>> interfaceToCommands = new HashMap<>();

        // 1. Procesamos las anotaciones individuales (@CreateCommand)
        for (Element element : roundEnv.getElementsAnnotatedWith(CreateCommand.class)) {
            addCommandInfo(element, element.getAnnotation(CreateCommand.class), interfaceToCommands);
        }

        // 2. Procesamos las anotaciones repetibles (@CreateCommands)
        for (Element element : roundEnv.getElementsAnnotatedWith(CreateCommands.class)) {
            CreateCommands commands = element.getAnnotation(CreateCommands.class);
            for (CreateCommand cmd : commands.value()) {
                addCommandInfo(element, cmd, interfaceToCommands);
            }
        }

        // Generamos un archivo de Interfaz por cada Request base
        for (Map.Entry<TypeElement, List<CommandInfo>> entry : interfaceToCommands.entrySet()) {
            generateAutoCommands(entry.getKey(), entry.getValue());
        }

        return true;
    }

    private void addCommandInfo(Element element, CreateCommand annotation, Map<TypeElement, List<CommandInfo>> map) {
        if (element.getKind() == ElementKind.CLASS) {
            TypeElement builderClass = (TypeElement) element;
            TypeElement enclosingInterface = (TypeElement) builderClass.getEnclosingElement();
            map.computeIfAbsent(enclosingInterface, k -> new ArrayList<>()).add(new CommandInfo(annotation, builderClass));
        }
    }

    private void generateAutoCommands(TypeElement requestInterface, List<CommandInfo> commands) {
        Elements elementUtils = processingEnv.getElementUtils();
        Types typeUtils = processingEnv.getTypeUtils();

        String interfaceName = requestInterface.getSimpleName().toString(); // ej. "IntakeRequest"
        String packageName = elementUtils.getPackageOf(requestInterface).getQualifiedName().toString();
        
        // Quitamos la palabra "Request" para que se llame "IntakeAutoCommands"
        String baseName = interfaceName.endsWith("Request") ? 
                          interfaceName.substring(0, interfaceName.length() - 7) : interfaceName;
        String autoCommandsName = baseName + "Commands";

        // Referencias de clases externas para JavaPoet
        ClassName commandClass = ClassName.get("edu.wpi.first.wpilibj2.command", "Command");
        ClassName supplierClass = ClassName.get("java.util.function", "Supplier");
        ClassName factoryClass = ClassName.get(packageName, interfaceName + "Factory");
        TypeName specificRequestType = TypeName.get(requestInterface.asType());

        // Empezamos a construir la Interfaz
        TypeSpec.Builder autoCommandsBuilder = TypeSpec.interfaceBuilder(autoCommandsName)
                .addModifiers(Modifier.PUBLIC);

        // Agregamos el método puente: Command setControl(Supplier<Request> requestSupplier);
        MethodSpec setControlMethod = MethodSpec.methodBuilder("setControl")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(commandClass)
                // ¡AQUÍ ESTÁ LA MAGIA! Pasamos el specificRequestType en vez de la clase base
                .addParameter(ParameterizedTypeName.get(supplierClass, specificRequestType), "request")
                .build();
        autoCommandsBuilder.addMethod(setControlMethod);

        // Generamos los métodos por cada anotación
        for (CommandInfo info : commands) {
            String className = info.builderClass.getSimpleName().toString();

            String factoryMethodName = className.substring(0, 1).toLowerCase() + className.substring(1);
            
            MethodSpec.Builder cmdMethod = MethodSpec.methodBuilder(info.annotation.name())
                    .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
                    .returns(commandClass);

            List<String> excludeList = Arrays.asList(info.annotation.exclude());
            CodeBlock.Builder codeBlock = CodeBlock.builder();
            
            // Iniciamos la cadena: return this.setControl(() -> IntakeRequestFactory.setAngle()
            codeBlock.add("return this.setControl(() -> \n    $T.$L()", factoryClass, factoryMethodName);

            // Set para asegurar que si dos métodos piden "tolerance", solo lo pongamos una vez en la firma
            Set<String> addedParamNames = new HashSet<>();

            // Escaneamos todos los métodos de la clase (ej. setAngle)
            for (Element member : elementUtils.getAllMembers(info.builderClass)) {
                if (member.getKind() == ElementKind.METHOD) {
                    ExecutableElement method = (ExecutableElement) member;
                    String methodName = method.getSimpleName().toString();

                    // Si el método devuelve su propia clase, es un método del Builder
                    if (typeUtils.isSameType(method.getReturnType(), info.builderClass.asType())) {
                        
                        // Revisamos si el usuario lo excluyó en la anotación
                        if (!excludeList.contains(methodName)) {
                            List<String> invokeParams = new ArrayList<>();
                            
                            // Agregamos los parámetros al Command autogenerado
                            for (VariableElement param : method.getParameters()) {
                                String pName = param.getSimpleName().toString();
                                if (!addedParamNames.contains(pName)) {
                                    cmdMethod.addParameter(TypeName.get(param.asType()), pName);
                                    addedParamNames.add(pName);
                                }
                                invokeParams.add(pName);
                            }
                            // Encadenamos el código: .withAngle(angle)
                            codeBlock.add("\n        .$L($L)", methodName, String.join(", ", invokeParams));
                        }
                    }
                }
            }
            
            // Cerramos la cadena
            codeBlock.add("\n);\n");
            cmdMethod.addCode(codeBlock.build());
            autoCommandsBuilder.addMethod(cmdMethod.build());
        }

        // Escribimos el archivo
        JavaFile javaFile = JavaFile.builder(packageName, autoCommandsBuilder.build())
                .indent("    ")
                .build();

        try {
            javaFile.writeTo(processingEnv.getFiler());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}