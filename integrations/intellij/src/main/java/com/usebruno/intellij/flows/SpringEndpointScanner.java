package com.usebruno.intellij.flows;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.psi.*;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import java.util.*;

/** Called under a smart read action; no PSI objects escape to the UI. */
final class SpringEndpointScanner {
    private static final String SPRING = "org.springframework.web.bind.annotation.";
    private static final Map<String, String> VERBS = Map.of(
            "GetMapping", "GET", "PostMapping", "POST", "PutMapping", "PUT",
            "DeleteMapping", "DELETE", "PatchMapping", "PATCH");
    record Result(List<ApiEndpoint> endpoints, List<String> warnings) {}
    private record Mapping(List<String> paths, List<String> verbs) {}

    static Result scan(Project project) {
        if (JavaPsiFacade.getInstance(project).findClass(SPRING + "RequestMapping", GlobalSearchScope.allScope(project)) == null) {
            return new Result(List.of(), List.of("Spring Web annotations were not found. Import the project's Maven/Gradle dependencies and wait for indexing. This explorer supports Java Spring controllers."));
        }
        List<ApiEndpoint> endpoints = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (var file : FilenameIndex.getAllFilesByExt(project, "java", GlobalSearchScope.projectScope(project))) {
            ProgressManager.checkCanceled();
            if (ProjectFileIndex.getInstance(project).isInTestSourceContent(file)) continue;
            if (PsiManager.getInstance(project).findFile(file) instanceof PsiJavaFile javaFile) {
                inspect(javaFile, endpoints, warnings);
            }
        }
        endpoints.sort(Comparator.comparing(ApiEndpoint::path).thenComparing(ApiEndpoint::method)
                .thenComparing(ApiEndpoint::controller));
        return new Result(List.copyOf(endpoints), List.copyOf(warnings));
    }

    static void inspect(PsiJavaFile file, List<ApiEndpoint> endpoints, List<String> warnings) {
        for (PsiClass type : file.getClasses()) inspectClass(type, endpoints, warnings);
    }

    static void inspectClass(PsiClass type, List<ApiEndpoint> endpoints, List<String> warnings) {
        ProgressManager.checkCanceled();
        for (PsiClass inner : type.getInnerClasses()) inspectClass(inner, endpoints, warnings);
        if (type.isInterface() || type.hasModifierProperty(PsiModifier.ABSTRACT) || !isController(type, new HashSet<>())) return;
        Mapping parent;
        try {
            parent = classMapping(type, new HashSet<>());
        } catch (IllegalArgumentException e) {
            warnings.add(type.getName() + ": " + e.getMessage());
            return;
        }
        if (parent == null) parent = new Mapping(List.of(""), List.of());
        Set<String> seen = new HashSet<>();
        var module = ModuleUtilCore.findModuleForPsiElement(type);
        String moduleName = module == null ? "Project" : module.getName();
        var origin = type.getContainingFile().getVirtualFile();
        for (PsiMethod method : type.getAllMethods()) {
            ProgressManager.checkCanceled();
            try {
                Mapping mapping = methodMapping(method, new HashSet<>());
                if (mapping == null) continue;
                // Spring combines type and method HTTP conditions as a union.
                Set<String> combined = new LinkedHashSet<>(parent.verbs());
                combined.addAll(mapping.verbs());
                List<String> verbs = new ArrayList<>(combined);
                if (verbs.isEmpty()) verbs = List.of("ANY");
                int variant = 0;
                for (String prefix : parent.paths()) for (String suffix : mapping.paths()) for (String verb : verbs) {
                    ProgressManager.checkCanceled();
                    String path = join(prefix, suffix);
                    String key = verb + " " + path + " " + method.getSignature(PsiSubstitutor.EMPTY);
                    if (!seen.add(key)) continue;
                    PsiFile source = method.getContainingFile();
                    endpoints.add(new ApiEndpoint(verb, path, Objects.toString(type.getQualifiedName(), type.getName()),
                            method.getName(), source.getVirtualFile() == null ? "" : source.getVirtualFile().getUrl(),
                            method.getTextOffset(), moduleName, origin == null ? "" : origin.getUrl(),
                            method.getName() + "(" + String.join(",", Arrays.stream(method.getParameterList().getParameters())
                                    .map(p -> p.getType().getCanonicalText()).toList()) + ")", variant++));
                }
            } catch (IllegalArgumentException e) {
                warnings.add(type.getName() + "." + method.getName() + ": " + e.getMessage());
            }
        }
    }

    private static boolean isController(PsiClass type, Set<PsiClass> seen) {
        ProgressManager.checkCanceled();
        if (!seen.add(type)) return false;
        for (PsiAnnotation annotation : type.getAnnotations()) {
            String name = annotation.getQualifiedName();
            if ((SPRING + "RestController").equals(name) || "org.springframework.stereotype.Controller".equals(name)) return true;
            PsiClass annotationType = annotation.resolveAnnotationType();
            if (annotationType != null && isController(annotationType, seen)) return true;
        }
        for (PsiClass parent : type.getSupers()) if (isController(parent, seen)) return true;
        return false;
    }

    private static Mapping classMapping(PsiClass type, Set<PsiClass> seen) {
        ProgressManager.checkCanceled();
        if (!seen.add(type)) return null;
        Mapping own = mapping(type);
        if (own != null) return own;
        for (PsiClass parent : type.getSupers()) {
            Mapping inherited = classMapping(parent, seen);
            if (inherited != null) return inherited;
        }
        return null;
    }

    private static Mapping methodMapping(PsiMethod method, Set<PsiMethod> seen) {
        ProgressManager.checkCanceled();
        if (!seen.add(method)) return null;
        Mapping own = mapping(method);
        if (own != null) return own;
        for (PsiMethod parent : method.findSuperMethods()) {
            Mapping inherited = methodMapping(parent, seen);
            if (inherited != null) return inherited;
        }
        return null;
    }

    private static Mapping mapping(PsiModifierListOwner owner) {
        for (PsiAnnotation annotation : owner.getAnnotations()) {
            String qualified = annotation.getQualifiedName();
            if (qualified == null || !qualified.startsWith(SPRING)) continue;
            String name = qualified.substring(SPRING.length());
            if (!name.equals("RequestMapping") && !VERBS.containsKey(name)) continue;
            List<String> paths = strings(annotation.findDeclaredAttributeValue("path"));
            if (paths.isEmpty()) paths = strings(annotation.findDeclaredAttributeValue("value"));
            if (paths.isEmpty()) paths = List.of("");
            List<String> verbs = new ArrayList<>();
            if (VERBS.containsKey(name)) verbs.add(VERBS.get(name));
            else {
                for (PsiAnnotationMemberValue value : values(annotation.findDeclaredAttributeValue("method"))) {
                    if (value instanceof PsiReferenceExpression reference && reference.resolve() instanceof PsiEnumConstant constant) {
                        verbs.add(constant.getName());
                    } else throw new IllegalArgumentException("Cannot resolve HTTP method: " + value.getText());
                }
            }
            return new Mapping(paths, verbs);
        }
        // Report composed mappings rather than silently inventing routes from unresolved aliases.
        for (PsiAnnotation annotation : owner.getAnnotations()) {
            PsiClass annotationType = annotation.resolveAnnotationType();
            if (annotationType != null && annotationType.getAnnotation(SPRING + "RequestMapping") != null) {
                throw new IllegalArgumentException("Custom composed mapping needs manual review: " + annotation.getQualifiedName());
            }
        }
        return null;
    }

    private static List<PsiAnnotationMemberValue> values(PsiAnnotationMemberValue value) {
        if (value == null) return List.of();
        if (value instanceof PsiArrayInitializerMemberValue array) return Arrays.asList(array.getInitializers());
        return List.of(value);
    }

    private static List<String> strings(PsiAnnotationMemberValue value) {
        List<String> result = new ArrayList<>();
        for (PsiAnnotationMemberValue entry : values(value)) {
            Object constant = JavaPsiFacade.getInstance(entry.getProject()).getConstantEvaluationHelper()
                    .computeConstantExpression(entry);
            if (!(constant instanceof String text)) {
                throw new IllegalArgumentException("Cannot resolve route constant: " + entry.getText());
            }
            result.add(text);
        }
        return result;
    }

    static String join(String prefix, String suffix) {
        if (prefix.isEmpty()) return suffix.startsWith("/") ? suffix : "/" + suffix;
        if (suffix.isEmpty()) return prefix.startsWith("/") ? prefix : "/" + prefix;
        return (prefix.startsWith("/") ? prefix : "/" + prefix).replaceAll("/+$", "")
                + (suffix.startsWith("/") ? suffix : "/" + suffix);
    }
}
