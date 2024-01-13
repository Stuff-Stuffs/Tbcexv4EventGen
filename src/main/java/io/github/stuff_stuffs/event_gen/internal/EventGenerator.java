package io.github.stuff_stuffs.event_gen.internal;

import com.squareup.javapoet.*;
import io.github.stuff_stuffs.event_gen.api.event.EventKey;
import io.github.stuff_stuffs.event_gen.api.event.gen.*;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

@SupportedAnnotationTypes({"io.github.stuff_stuffs.event_gen.api.event.gen.EventInfo"})
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public final class EventGenerator extends AbstractProcessor {
    @Override
    public boolean process(final Set<? extends TypeElement> annotations, final RoundEnvironment roundEnv) {
        final Map<String, List<FieldSpec>> entries = new HashMap<>();
        for (final TypeElement annotation : annotations) {
            for (final Element annotatedElement : roundEnv.getElementsAnnotatedWith(annotation)) {
                if (annotatedElement.getKind() != ElementKind.METHOD) {
                    throw new IllegalStateException();
                }
                final KeyEntry entry = createEventFile((ExecutableElement) annotatedElement);
                entries.computeIfAbsent(entry.keyFile(), s -> new ArrayList<>()).add(entry.spec());
            }
        }
        for (final Map.Entry<String, List<FieldSpec>> entry : entries.entrySet()) {
            createKeyFile(entry.getKey(), entry.getValue());
        }
        return true;
    }

    private void createKeyFile(final String loc, final List<FieldSpec> keys) {
        final int index = loc.lastIndexOf('.');
        final String name;
        final String packageLoc;
        if (index == -1) {
            packageLoc = "";
            name = loc;
        } else {
            packageLoc = loc.substring(0, index);
            name = loc.substring(index + 1);
        }
        final TypeSpec spec = TypeSpec
                .classBuilder(name)
                .addFields(keys)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addMethod(
                        MethodSpec
                                .constructorBuilder()
                                .addModifiers(Modifier.PRIVATE)
                                .build()
                ).build();

        final JavaFile file = JavaFile.builder(packageLoc, spec).build();
        try (final var writer = processingEnv.getFiler().createSourceFile(loc).openWriter()) {
            writer.write(file.toString());
        } catch (final IOException e) {
            e.printStackTrace();
        }
    }

    private KeyEntry createEventFile(final ExecutableElement executableElement) {
        final EventInfo eventInfo = executableElement.getAnnotation(EventInfo.class);
        final EventComparisonInfo compareInfo = executableElement.getAnnotation(EventComparisonInfo.class);
        final EventKeyLocation keyLocation = findKeyLocation(executableElement);
        final String name = executableElement.getSimpleName().toString();
        final String packageLoc = findPackage(executableElement);
        final TypeSpec.Builder builder = TypeSpec.interfaceBuilder(name);
        builder.addModifiers(Modifier.PUBLIC);
        final TypeSpec.Builder view = TypeSpec.interfaceBuilder("View");
        view.addModifiers(Modifier.PUBLIC, Modifier.STATIC);
        final MethodSpec eventMethod = createEventMethod(executableElement);
        if (compareInfo != null) {
            final MethodSpec compareMethod = createCompareMethod(compareInfo);
            builder.addMethod(compareMethod);
            view.addMethod(compareMethod);
        }
        builder.addMethod(eventMethod);
        final MethodSpec viewMethod = createViewEventMethod(executableElement);
        view.addMethod(viewMethod);
        builder.addType(view.build());
        final ClassName className = ClassName.get(packageLoc, name);
        final MethodSpec convertSpec = createConverterMethod(className, eventMethod, viewMethod, executableElement, eventInfo, compareInfo);
        final MethodSpec invokerSpec = createInvokerMethod(className, eventMethod, eventInfo, compareInfo);
        MethodSpec delaySpec = createDelayMethod(className, eventMethod, eventInfo, compareInfo);
        final TypeSpec factoryClass = TypeSpec
                .anonymousClassBuilder("")
                .addSuperinterface(
                        ParameterizedTypeName.get(
                                ClassName.get(EventKey.Factory.class),
                                className,
                                className.nestedClass("View")
                        )
                )
                .addMethod(convertSpec)
                .addMethod(invokerSpec)
                .addMethod(delaySpec)
                .build();
        final MethodSpec factoryMethod = MethodSpec
                .methodBuilder("factory")
                .returns(
                        ParameterizedTypeName.get(
                                ClassName.get(EventKey.Factory.class),
                                className,
                                className.nestedClass("View")
                        )
                )
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addCode(CodeBlock.builder().addStatement("return $L", factoryClass).build()).build();
        builder.addMethod(factoryMethod);
        final JavaFile file = JavaFile.builder(packageLoc, builder.build()).indent("    ").build();
        try (final var writer = processingEnv.getFiler().createSourceFile(packageLoc + "." + name).openWriter()) {
            writer.write(file.toString());
        } catch (final IOException e) {
            e.printStackTrace();
        }
        final String initializer;
        final List<Object> args;
        if (compareInfo == null) {
            initializer = "new EventKey<>($T.class, $T.class, null)";
            args = List.of(className, className.nestedClass("View"));
        } else {
            initializer = "new EventKey<>($T.class, $T.class, java.util.Comparator.comparing($T::ord, $L))";
            args = List.of(className, className.nestedClass("View"), className, compareInfo.comparator());
        }
        final FieldSpec keySpec = FieldSpec
                .builder(
                        ParameterizedTypeName.get(
                                ClassName.get(EventKey.class),
                                className,
                                className.nestedClass("View")
                        ),
                        parse(name) + "_KEY",
                        Modifier.PUBLIC,
                        Modifier.STATIC,
                        Modifier.FINAL

                )
                .initializer(initializer, args.toArray())
                .build();
        return new KeyEntry(keyLocation.location(), keySpec);
    }

    private String parse(final String name) {
        final int length = name.length();
        final StringBuilder builder = new StringBuilder(length * 2);
        for (int i = 0; i < length; i++) {
            if (i != 0 && Character.isUpperCase(name.charAt(i))) {
                builder.append('_');
            }
            builder.append(Character.toUpperCase(name.charAt(i)));
        }
        return builder.toString();
    }

    private MethodSpec createCompareMethod(final EventComparisonInfo info) {
        return MethodSpec
                .methodBuilder("ord")
                .addModifiers(
                        Modifier.PUBLIC,
                        Modifier.ABSTRACT
                )
                .returns(
                        TypeName.get(
                                mirrorFromCompareInfo(info)
                        )
                )
                .build();
    }

    private MethodSpec createDelayMethod(final ClassName className, final MethodSpec eventMethod, final EventInfo eventInfo, final EventComparisonInfo compareInfo) {
        String params = "";
        boolean any = false;
        final List<Object> args = new ArrayList<>();
        args.add(eventMethod);
        for (final ParameterSpec parameter : eventMethod.parameters) {
            if (any) {
                params = params + ",";
            }
            params = params + "$N";
            args.add(parameter.name);
            any = true;
        }
        final TypeSpec runDelegate = TypeSpec.anonymousClassBuilder("")
                .addSuperinterface(Runnable.class)
                .addMethod(
                        MethodSpec.methodBuilder("run")
                                .addModifiers(Modifier.PUBLIC)
                                .addAnnotation(Override.class)
                                .addCode(
                                        CodeBlock.builder()
                                                .addStatement("delegate.$N(" + params + ")", args.toArray())
                                                .build()
                                )
                                .build()
                ).build();
        final MethodSpec.Builder wrapperMethod = MethodSpec.methodBuilder(eventMethod.name)
                .addModifiers(Modifier.PUBLIC)
                .returns(eventMethod.returnType)
                .addTypeVariables(eventMethod.typeVariables)
                .addAnnotation(Override.class);
        for (final ParameterSpec parameter : eventMethod.parameters) {
            wrapperMethod.addParameter(parameter);
        }
        if (eventMethod.returnType.equals(TypeName.VOID)) {
            wrapperMethod.addCode(
                    CodeBlock.builder()
                            .addStatement("consumer.accept($L)", runDelegate)
                            .addStatement("return")
                            .build()
            );
        } else {
            wrapperMethod.addCode(
                    CodeBlock.builder()
                            .addStatement("consumer.accept($L)", runDelegate)
                            .addStatement("return " + eventInfo.defaultValue())
                            .build()
            );
        }
        final TypeSpec.Builder wrapper = TypeSpec.anonymousClassBuilder("").addSuperinterface(className).addMethod(wrapperMethod.build());
        if (compareInfo != null) {
            final MethodSpec ordSpec = MethodSpec
                    .methodBuilder("ord")
                    .addModifiers(Modifier.PUBLIC)
                    .returns(ClassName.get(mirrorFromCompareInfo(compareInfo)))
                    .addStatement(
                            "throw new $T(\"Somebody tried to sort an invoker!\")",
                            ClassName.get(UnsupportedOperationException.class)
                    )
                    .build();
            wrapper.addMethod(ordSpec);
        }
        return MethodSpec
                .methodBuilder("delay")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .addParameter(
                        className,
                        "delegate"
                )
                .addParameter(
                        ParameterizedTypeName.get(
                                Consumer.class,
                                Runnable.class
                        ),
                        "consumer"
                )
                .returns(className)
                .addCode(
                        "return $L;",
                        wrapper.build()
                )
                .build();
    }

    private MethodSpec createInvokerMethod(final ClassName className, final MethodSpec eventMethod, final EventInfo eventInfo, final EventComparisonInfo compareInfo) {
        String params = "";
        boolean any = false;
        final List<Object> args = new ArrayList<>();
        args.add(eventMethod);
        for (final ParameterSpec parameter : eventMethod.parameters) {
            if (any) {
                params = params + ",";
            }
            params = params + "$N";
            args.add(parameter.name);
            any = true;
        }
        final CodeBlock code;
        if (eventMethod.returnType.equals(TypeName.VOID)) {
            code = CodeBlock
                    .builder()
                    .add("for(var event: events) {\n")
                    .indent()
                    .addStatement("event.$N(" + params + ")", args.toArray())
                    .unindent()
                    .add("}\n")
                    .addStatement("return")
                    .build();
        } else {
            code = CodeBlock
                    .builder()
                    .addStatement("var res = " + eventInfo.defaultValue())
                    .add("for(var event: events) {\n")
                    .indent()
                    .addStatement("final var r = event.$N(" + params + ")", args.toArray())
                    .addStatement("res = " + eventInfo.combiner() + "(res, r)")
                    .unindent()
                    .add("}\n")
                    .addStatement("return res")
                    .build();
        }
        final MethodSpec invokeSpec = MethodSpec
                .methodBuilder(eventMethod.name)
                .returns(eventMethod.returnType)
                .addTypeVariables(eventMethod.typeVariables)
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameters(eventMethod.parameters)
                .addCode(code)
                .build();
        final TypeSpec.Builder builder = TypeSpec.anonymousClassBuilder("").addSuperinterface(className).addMethod(invokeSpec);
        if (compareInfo != null) {
            final MethodSpec ordSpec = MethodSpec
                    .methodBuilder("ord")
                    .addModifiers(Modifier.PUBLIC)
                    .returns(ClassName.get(mirrorFromCompareInfo(compareInfo)))
                    .addStatement(
                            "throw new $T(\"Somebody tried to sort an invoker!\")",
                            ClassName.get(UnsupportedOperationException.class)
                    )
                    .build();
            builder.addMethod(ordSpec);
        }
        final TypeSpec anon = builder.build();
        return MethodSpec
                .methodBuilder("invoker")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(
                        ParameterizedTypeName.get(
                                ClassName.get(List.class),
                                className
                        ),
                        "events"
                )
                .returns(className)
                .addCode(
                        "return $L;",
                        anon
                )
                .build();
    }

    private TypeSpec createConvertedClass(final ClassName mutName, final MethodSpec mutMethod, final ClassName viewName, final MethodSpec viewMethod, final ExecutableElement element, final EventInfo info, /*Nullable*/ final EventComparisonInfo compareInfo) {
        String methodCall = "view.$N(";
        final List<String> args = new ArrayList<>();
        args.add(viewMethod.name);
        boolean any = false;
        for (final ParameterSpec parameterSpec : mutMethod.parameters) {
            if (any) {
                methodCall = methodCall + ", ";
            }
            methodCall = methodCall + "$N";
            args.add(parameterSpec.name);
            any = true;
        }
        methodCall = methodCall + ")";
        final CodeBlock codeBlock = CodeBlock.builder().addStatement(methodCall, args.toArray()).addStatement(info.defaultValue().isEmpty() ? "return" : "return " + info.defaultValue()).build();
        final TypeSpec.Builder builder = TypeSpec
                .anonymousClassBuilder("")
                .addSuperinterface(mutName)
                .addMethod(
                        MethodSpec
                                .methodBuilder("on" + element.getSimpleName().toString())
                                .addAnnotation(Override.class)
                                .addModifiers(Modifier.PUBLIC)
                                .addParameters(mutMethod.parameters)
                                .addTypeVariables(mutMethod.typeVariables)
                                .returns(mutMethod.returnType)
                                .addCode(
                                        codeBlock
                                ).build()
                );
        if (compareInfo != null) {
            final MethodSpec ordSpec = MethodSpec
                    .methodBuilder("ord").addAnnotation(Override.class)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(
                            TypeName.get(
                                    mirrorFromCompareInfo(
                                            compareInfo
                                    )
                            )
                    )
                    .addStatement(
                            "return view.ord()"
                    )
                    .build();
            builder.addMethod(ordSpec);
        }
        return builder.build();
    }

    private MethodSpec createConverterMethod(final ClassName className, final MethodSpec eventMethod, final MethodSpec viewMethod, final ExecutableElement executableElement, final EventInfo eventInfo, /*Nullable*/ final EventComparisonInfo compareInfo) {
        return MethodSpec.methodBuilder("convert")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .addParameter(className.nestedClass("View"), "view")
                .returns(className)
                .addCode(CodeBlock
                        .builder()
                        .add(
                                "return $L;\n",
                                createConvertedClass(className, eventMethod, className.nestedClass("View"), viewMethod, executableElement, eventInfo, compareInfo)
                        ).build()
                ).build();
    }

    private MethodSpec createEventMethod(final ExecutableElement method) {
        final String name = method.getSimpleName().toString();
        final MethodSpec.Builder eventMethodSpec = MethodSpec.methodBuilder("on" + name).returns(TypeName.get(method.getReturnType())).addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT);
        for (final VariableElement parameter : method.getParameters()) {
            final ParameterSpec parameterSpec = ParameterSpec.get(parameter);
            eventMethodSpec.addParameter(parameterSpec);
        }
        final List<? extends TypeParameterElement> parameters = method.getTypeParameters();
        for (final TypeParameterElement parameter : parameters) {
            eventMethodSpec.addTypeVariable(TypeVariableName.get(parameter));
        }
        return eventMethodSpec.build();
    }

    private MethodSpec createViewEventMethod(final ExecutableElement method) {
        final String name = method.getSimpleName().toString();
        final MethodSpec.Builder eventMethodSpec = MethodSpec.methodBuilder("on" + name).returns(TypeName.VOID).addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT);
        for (final VariableElement parameter : method.getParameters()) {
            final TypeMirror mirror = walkUp(parameter.asType());
            eventMethodSpec.addParameter(ParameterSpec.builder(TypeName.get(mirror), parameter.getSimpleName().toString()).build());
        }
        final List<? extends TypeParameterElement> parameters = method.getTypeParameters();
        for (final TypeParameterElement parameter : parameters) {
            eventMethodSpec.addTypeVariable(TypeVariableName.get(parameter));
        }
        return eventMethodSpec.build();
    }

    private TypeMirror walkUp(final TypeMirror type) {
        if (type.getKind() == TypeKind.DECLARED) {
            final EventViewable annotation = ((DeclaredType) type).asElement().getAnnotation(EventViewable.class);
            if (annotation != null) {
                final TypeMirror mirror = mirrorFromViewable(annotation);
                final Element element = processingEnv.getTypeUtils().asElement(mirror);
                final ElementKind kind = element.getKind();
                if (!kind.isClass() && !kind.isInterface()) {
                    throw new RuntimeException("Invalid View class!");
                }
                final TypeElement typeElement = (TypeElement) element;
                final Types utils = processingEnv.getTypeUtils();
                if (mirror.getKind() == TypeKind.DECLARED) {
                    final List<? extends TypeMirror> mutArguments = ((DeclaredType) type).getTypeArguments();
                    final DeclaredType withArgs = utils.getDeclaredType(typeElement, mutArguments.toArray(TypeMirror[]::new));
                    if (!utils.isSubtype(type, withArgs)) {
                        throw new RuntimeException("Mut not a subtype of View! " + type + " " + withArgs);
                    }
                    return walkUp(withArgs);
                } else {
                    if (!utils.isSubtype(type, mirror)) {
                        throw new RuntimeException("Mut not a subtype of View! " + type + " " + mirror);
                    }
                    return mirror;
                }
            }
        }
        return type;
    }

    private TypeMirror mirrorFromCompareInfo(final EventComparisonInfo info) {
        try {
            info.comparedType().isInstance(null);
        } catch (final MirroredTypeException e) {
            return e.getTypeMirror();
        }
        throw new AssertionError();
    }

    private TypeMirror mirrorFromViewable(final EventViewable viewable) {
        try {
            viewable.viewClass().isInstance(null);
        } catch (final MirroredTypeException e) {
            return e.getTypeMirror();
        }
        throw new AssertionError();
    }

    private String findPackage(Element element) {
        while (element != null) {
            final EventPackageLocation annotation = element.getAnnotation(EventPackageLocation.class);
            if (annotation != null) {
                return annotation.value();
            }
            element = element.getEnclosingElement();
        }
        throw new IllegalStateException("Unspecified package location!");
    }

    private EventKeyLocation findKeyLocation(Element element) {
        while (element != null) {
            final EventKeyLocation annotation = element.getAnnotation(EventKeyLocation.class);
            if (annotation != null) {
                return annotation;
            }
            element = element.getEnclosingElement();
        }
        throw new IllegalStateException("Unspecified key location!");
    }

    private record KeyEntry(String keyFile, FieldSpec spec) {
    }
}
