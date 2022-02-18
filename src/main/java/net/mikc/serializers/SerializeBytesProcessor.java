package net.mikc.serializers;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;

@SupportedAnnotationTypes({"net.mikc.serializers.SerializeBytes"})
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class SerializeBytesProcessor extends AbstractProcessor {

    private Messager messager;
    private Types typeUtils;
    private Elements elementUtils;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {

        for (TypeElement annotation : annotations) {
            Map<String, List<SerializationOrdering>> orderedAnnotations = new HashMap<>();
            Set<? extends Element> annotatedElements = roundEnv.getElementsAnnotatedWith(annotation);
            Map<String, Boolean> typeArrayDeclaredBefore = new HashMap<>();
            Map<String, String> enumClassName = new HashMap<>();
            for (Element element : annotatedElements) {
                SerializeBytes serializeBytes = element.getAnnotation(SerializeBytes.class);

                if (element.getKind() == ElementKind.FIELD) {
                    if (element.getModifiers().contains(Modifier.STATIC)) {
                        printError(element, "static not supported");
                    }
                    TypeMirror typeMirror = element.asType();
                    String className = ((TypeElement) element.getEnclosingElement()).getQualifiedName().toString();
                    List<SerializationOrdering> containers = orderedAnnotations.computeIfAbsent(className, k -> new ArrayList<>());
                    containers.add(new SerializationOrdering(element, serializeBytes.id(), serializeBytes.isEnum(), typeMirror));
                }
            }
            // compile now
            for (String className : orderedAnnotations.keySet()) {
                List<SerializationOrdering> annotationList = orderedAnnotations.get(className);
                Collections.sort(annotationList, (Comparator<SerializationOrdering>) (o1, o2) -> {
                    if (o1.getOrder().equals(o2.getOrder()))
                        return 0;
                    if (o1.getOrder() > o2.getOrder())
                        return 1;

                    return -1;
                });
                StringBuilder serializationSource = new StringBuilder();
                StringBuilder deserializationSource = new StringBuilder();
                for (SerializationOrdering serializeAnnotation : annotationList) {

                    String varName = serializeAnnotation.getElement().getSimpleName().toString();
                    String setter = "b."+varName;
                    String getter = "entity.get"+capitalizeFirstLetter(varName);
                    String typeName = serializeAnnotation.getTypeMirror().toString();
                    enumClassName.put(setter, typeName);

                    if (typeName.startsWith(KnownTypes.ListType)) {
                        String genericTypeName = genericType(typeName);
                        String arrayVar = nameFromType(genericTypeName) + "_ArrayList";
                        Boolean declared = typeArrayDeclaredBefore.get(genericTypeName);
                        if(declared == null || !declared) {
                            deserializationSource.append("\t\tList<").append(genericTypeName).append("> ").append(arrayVar).append(" = new ArrayList<>();\n");
                        } else {
                            deserializationSource.append("\t\t").append(arrayVar).append(".clear();\n");
                        }
                        deserializationSource.append("\t\tlen=bb.getInt();\n");
                        deserializationSource.append("\t\tfor(int i=0; i<len; i++) {\n");
                        serializationSource.append("\t\tbs.writeInt(").append(getter).append("().size());\n");
                        serializationSource.append("\t\tfor(int i=0; i<").append(getter).append("().size(); i++) {\n");
//                        deserializationSource.append("//XXX\n");
//                        deserializationSource.append("System.out.println(i);\n");
                        handlePrimitiveType(serializationSource, deserializationSource, arrayVar+".add", getter, genericTypeName, serializeAnnotation.isEnum(), serializeAnnotation.getElement(), ".get(i)", enumClassName);
                        serializationSource.append("\t\t}\n");
                        deserializationSource.append("\t\t}\n");
                        deserializationSource.append("\t\t").append(setter).append("(").append(arrayVar).append(");\n");
                    } else {
                        handlePrimitiveType(serializationSource, deserializationSource, setter, getter, typeName, serializeAnnotation.isEnum(), serializeAnnotation.getElement(), "", enumClassName);
                    }

                }
                //printWarning(null, "XXX class="+className+"source="+source);
                try {
                    writeCompiledClass(className, serializationSource.toString(), deserializationSource.toString());
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }

        }


        // don't claim annotations to allow other processors to process them
        return false;
    }

    private void handlePrimitiveType(StringBuilder serializationSource, StringBuilder deserializationSource, String varName, String getter, String typeName, boolean isEnum, Element element, String varSuffix, Map<String, String> enumClassName) {
        if (isEnum) {
            String enumClazz = enumClassName.get(varName);
            if(enumClazz==null) {
                throw new IllegalArgumentException("can't compile enum "+varName+" enum class type not found!");
            }
            serializationSource.append("\t\tbs.writeInt(").append(getter).append("()").append(varSuffix).append(".getValue());\n");
            deserializationSource.append("\t\t").append(varName).append("(").append(enumClazz).append(".fromValue(bb.getInt()));\n");
            return;
        }
        switch (typeName) {
            case KnownTypes.BooleanType:
                serializationSource.append("\t\tbs.writeBoolean(").append(getter).append("()").append(varSuffix).append(");\n");
                deserializationSource.append("\t\tch=bb.get();\n");
                deserializationSource.append("\t\t").append(varName).append("(ch==0?false:true);\n");
                break;
            case KnownTypes.IntegerType:
                serializationSource.append("\t\tbs.writeInt(").append(getter).append("()").append(varSuffix).append(");\n");
                deserializationSource.append("\t\t").append(varName).append("(bb.getInt());\n");
                break;
            case KnownTypes.LongType:
                serializationSource.append("\t\tbs.writeLong(").append(getter).append("()").append(varSuffix).append(");\n");
                deserializationSource.append("\t\t").append(varName).append("(bb.getLong());\n");
                break;
            case KnownTypes.ShortType:
                serializationSource.append("\t\tbs.writeShort(").append(getter).append("()").append(varSuffix).append(");\n");
                deserializationSource.append("\t\t").append(varName).append("(bb.getShort());\n");
                break;
            case KnownTypes.FloatType:
                serializationSource.append("\t\tbs.writeFloat(").append(getter).append("()").append(varSuffix).append(");\n");
                deserializationSource.append("\t\t").append(varName).append("(bb.getFloat());\n");
                break;
            case KnownTypes.DoubleType:
                serializationSource.append("\t\tbs.writeDouble(").append(getter).append("()").append(varSuffix).append(");\n");
                deserializationSource.append("\t\t").append(varName).append("(bb.getDouble());\n");
                break;
            case KnownTypes.ByteType:
                serializationSource.append("\t\tbs.writeByte(").append(getter).append("()").append(varSuffix).append(");\n");
                deserializationSource.append("\t\t").append(varName).append("(bb.get());\n");
                break;
            case KnownTypes.CharType:
                serializationSource.append("\t\tbs.writeChar(").append(getter).append("()").append(varSuffix).append(");\n");
                deserializationSource.append("\t\t").append(varName).append("(bb.getChar());\n");
                break;
            case KnownTypes.StringType:
                serializationSource.append("\t\tbs.writeInt(").append(getter).append("()").append(varSuffix).append(".length());\n");
                serializationSource.append("\t\tbs.write(").append(getter).append("()").append(varSuffix).append(".getBytes(StandardCharsets.UTF_8));\n");
                deserializationSource.append("\t\tl=bb.getInt();\n");
                deserializationSource.append("\t\tsb=new byte[l];\n");
                deserializationSource.append("\t\tbb.get(sb);\n");
                deserializationSource.append("\t\t").append(varName).append("(new String(sb, StandardCharsets.UTF_8));\n");
                break;
            case KnownTypes.NumberType:
            case KnownTypes.VoidType:
            case KnownTypes.ObjectType:
            default:
                printError(element, "Don't know how to serialize: " + typeName);
                throw new IllegalArgumentException(typeName);
        }
    }

    private void writeCompiledClass(String className, String serializationSource, String deserializationSource) throws IOException {
        if ("".equals(className)) return;
        String packageName = null;
        int lastDot = className.lastIndexOf('.');
        if (lastDot > 0) {
            packageName = className.substring(0, lastDot);
        }

        String simpleClassName = className.substring(lastDot + 1);
        String builderClassName = className + "Serializer";
        String builderSimpleClassName = builderClassName
                .substring(lastDot + 1);


        JavaFileObject builderFile = processingEnv.getFiler()
                .createSourceFile(builderClassName);

        try (PrintWriter out = new PrintWriter(builderFile.openWriter())) {
            out.write("package " + packageName + ";\n\n");
            out.write("import com.google.common.io.ByteArrayDataOutput;\n" +
                    "import com.google.common.io.ByteStreams;\n" +
                    "import java.nio.ByteBuffer;\n\n" +
                    "import java.util.ArrayList;\n" +
                    "import java.util.List;\n" +
                    "\n" +
                    "import java.nio.charset.StandardCharsets;\n\n");
            out.write("public class " + builderSimpleClassName + " {\n\n");
            out.write("\tpublic static byte[] serialize(" + className + " entity) {\n");
            out.write("\t\tByteArrayDataOutput bs = ByteStreams.newDataOutput();\n");
            out.write(serializationSource);
            out.write("\t\treturn bs.toByteArray();\n");
            out.write("\t}\n\n");
            out.write("\tpublic static " + className + " deserialize(byte[] bytes) {\n");
            out.write("\t\tbyte ch;\n");
            out.write("\t\tbyte[] sb=null;\n");
            out.write("\t\tint l, len;\n");
            out.write("\t\tByteBuffer bb = ByteBuffer.wrap(bytes);\n" +
                    "\t\t"+className+".Builder b = "+className+".builder();\n");
            out.write(deserializationSource);
            out.write("\t\treturn b.build();\n");
            out.write("\t}\n");
            out.write("}\n");
        }
    }

    private void checkMethod(ExecutableElement method) {
        // check for valid name
        String name = method.getSimpleName().toString();
        if (!name.startsWith("set")) {
            printError(method, "setter name must start with \"set\"");
        } else if (name.length() == 3) {
            printError(method, "the method name must contain more than just \"set\"");
        } else if (Character.isLowerCase(name.charAt(3))) {
            if (method.getParameters().size() != 1) {
                printError(method, "character following \"set\" must be upper case");
            }
        }

        // check, if setter is public
        if (!method.getModifiers().contains(Modifier.PUBLIC)) {
            printError(method, "setter must be public");
        }

        // check, if method is static
        if (method.getModifiers().contains(Modifier.STATIC)) {
            printError(method, "setter must not be static");
        }
    }

    private void printError(Element element, String message) {
        messager.printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    private void printWarning(Element element, String message) {
        message += "\n";
        try {
            Files.write(
                    Paths.get("annotation.log"),
                    message.getBytes(),
                    StandardOpenOption.APPEND);
            if (element != null)
                messager.printMessage(Diagnostic.Kind.WARNING, message, element);
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    @Override
    public void init(ProcessingEnvironment processingEnvironment) {
        super.init(processingEnvironment);

        // get messager for printing errors
        messager = processingEnvironment.getMessager();
        typeUtils = processingEnvironment.getTypeUtils();
        elementUtils = processingEnvironment.getElementUtils();
    }

    private static String genericType(String enclosedType) {
        int start = enclosedType.indexOf("<") + 1;
        int end = enclosedType.lastIndexOf(">");
        if (start > 0 && end > start) {
            return enclosedType.substring(start, end);
        } else {
            return null;
        }
    }

    private static String capitalizeFirstLetter(String name) {
        return name.substring(0,1).toUpperCase() + name.substring(1);
    }

    private static String nameFromType(String value) {
        return value.replaceAll("[^A-Za-z0-9]", "_");
    }

}

