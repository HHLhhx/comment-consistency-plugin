package com.nju.comment.util;

import com.intellij.codeInsight.AnnotationUtil;
import com.intellij.codeInsight.ExceptionUtil;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.CommonClassNames;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiNameHelper;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiReturnStatement;
import com.intellij.psi.PsiThrowStatement;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.MethodSignatureUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.nju.comment.pojo.MethodValidationResult;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public final class MethodValidationUtil {

    private MethodValidationUtil() {
    }

    public static boolean isQuicklyEligible(PsiMethod method) {
        if (method == null || !method.isValid()) {
            return false;
        }
        Project project = method.getProject();
        if (project.isDisposed() || DumbService.isDumb(project)) {
            return false;
        }

        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null || !containingClass.isValid()) {
            return false;
        }
        if (containingClass.getQualifiedName() == null || containingClass.isInterface()) {
            return false;
        }
        if (method.hasModifierProperty(PsiModifier.ABSTRACT)) {
            return false;
        }
        if (method.getBody() == null) {
            return false;
        }

        return PsiTreeUtil.findChildOfType(method, PsiErrorElement.class) == null;
    }

    public static MethodValidationResult validateCompilable(PsiMethod method) {
        long sourceStamp = MethodRecordUtil.getSourceStamp(method);
        if (method == null || !method.isValid()) {
            return MethodValidationResult.invalid("Method PSI is invalid", sourceStamp);
        }

        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null || !containingClass.isValid()) {
            return MethodValidationResult.invalid("Containing class is invalid", sourceStamp);
        }

        if (containingClass.getQualifiedName() == null) {
            return MethodValidationResult.invalid("Containing class has no qualified name", sourceStamp);
        }

        if (isInterfaceOrAbstract(method, containingClass)) {
            return MethodValidationResult.invalid("Method is abstract or belongs to an interface", sourceStamp);
        }

        String reason;
        reason = validateMethodName(method, containingClass);
        if (reason != null) return MethodValidationResult.invalid(reason, sourceStamp);

        reason = validateModifier(method, containingClass);
        if (reason != null) return MethodValidationResult.invalid(reason, sourceStamp);

        reason = validateReturnType(method, containingClass);
        if (reason != null) return MethodValidationResult.invalid(reason, sourceStamp);

        reason = validateParameterList(method);
        if (reason != null) return MethodValidationResult.invalid(reason, sourceStamp);

        reason = validateTypeParameter(method);
        if (reason != null) return MethodValidationResult.invalid(reason, sourceStamp);

        reason = validateThrowsList(method);
        if (reason != null) return MethodValidationResult.invalid(reason, sourceStamp);

        reason = validateMethodBody(method, containingClass);
        if (reason != null) return MethodValidationResult.invalid(reason, sourceStamp);

        reason = validateCheckedExceptions(method);
        if (reason != null) return MethodValidationResult.invalid(reason, sourceStamp);

        reason = validateOverride(method);
        if (reason != null) return MethodValidationResult.invalid(reason, sourceStamp);

        reason = validateUniqueSignature(method, containingClass);
        if (reason != null) return MethodValidationResult.invalid(reason, sourceStamp);

        reason = validatePsiReferences(method);
        if (reason != null) return MethodValidationResult.invalid(reason, sourceStamp);

        reason = validateReturnStatements(method);
        if (reason != null) return MethodValidationResult.invalid(reason, sourceStamp);

        return MethodValidationResult.valid(sourceStamp);
    }

    private static boolean isInterfaceOrAbstract(PsiMethod method, PsiClass containingClass) {
        return containingClass.isInterface() || method.hasModifierProperty(PsiModifier.ABSTRACT);
    }

    private static String validateCheckedExceptions(PsiMethod method) {
        PsiCodeBlock body = method.getBody();
        if (body == null) {
            return null;
        }

        PsiClassType[] unhandled = ExceptionUtil.getUnhandledExceptions(body).toArray(new PsiClassType[0]);
        if (unhandled.length == 0) {
            return null;
        }

        PsiClassType[] declared = method.getThrowsList().getReferencedTypes();
        Project project = method.getProject();
        PsiClass runtimeEx = JavaPsiFacade.getInstance(project)
                .findClass(CommonClassNames.JAVA_LANG_RUNTIME_EXCEPTION, GlobalSearchScope.allScope(project));
        PsiClass errorEx = JavaPsiFacade.getInstance(project)
                .findClass(CommonClassNames.JAVA_LANG_ERROR, GlobalSearchScope.allScope(project));

        for (PsiClassType type : unhandled) {
            PsiClass exCls = type.resolve();
            if (exCls == null) {
                return "Unhandled exception type cannot be resolved";
            }

            boolean isUnchecked = (runtimeEx != null && exCls.isInheritor(runtimeEx, true))
                    || (errorEx != null && exCls.isInheritor(errorEx, true))
                    || CommonClassNames.JAVA_LANG_RUNTIME_EXCEPTION.equals(exCls.getQualifiedName())
                    || CommonClassNames.JAVA_LANG_ERROR.equals(exCls.getQualifiedName());
            if (isUnchecked) {
                continue;
            }

            boolean covered = false;
            for (PsiClassType decType : declared) {
                PsiClass decCls = decType.resolve();
                if (decCls == null) {
                    continue;
                }
                if (exCls.isInheritor(decCls, true) || exCls.equals(decCls)) {
                    covered = true;
                    break;
                }
            }

            if (!covered) {
                return "Checked exception is not caught or declared";
            }
        }

        return null;
    }

    private static String validateMethodName(PsiMethod method, PsiClass containingClass) {
        if (method.getNameIdentifier() == null) {
            return "Method name identifier is missing";
        }

        String name = method.getName();
        PsiNameHelper nameHelper = PsiNameHelper.getInstance(method.getProject());

        if (method.isConstructor()) {
            if (!Objects.equals(containingClass.getName(), name)) {
                return "Constructor name does not match class name";
            }
        } else if (!nameHelper.isIdentifier(name)) {
            return "Method name is not a valid Java identifier";
        }

        return null;
    }

    private static String validateModifier(PsiMethod method, PsiClass containingClass) {
        boolean isAbstract = method.hasModifierProperty(PsiModifier.ABSTRACT);
        boolean isFinal = method.hasModifierProperty(PsiModifier.FINAL);
        boolean isPrivate = method.hasModifierProperty(PsiModifier.PRIVATE);
        boolean isStatic = method.hasModifierProperty(PsiModifier.STATIC);
        boolean isNative = method.hasModifierProperty(PsiModifier.NATIVE);
        boolean isSynchronized = method.hasModifierProperty(PsiModifier.SYNCHRONIZED);
        boolean isStrictfp = method.hasModifierProperty(PsiModifier.STRICTFP);
        boolean isDefault = method.hasModifierProperty(PsiModifier.DEFAULT);

        if (isAbstract && (isFinal || isPrivate || isStatic || isNative || isSynchronized || isStrictfp || isDefault)) {
            return "Abstract method has conflicting modifiers";
        }

        if (method.isConstructor() && (isAbstract || isFinal || isStatic || isNative || isSynchronized || isStrictfp)) {
            return "Constructor has illegal modifiers";
        }

        if (containingClass.isAnnotationType()) {
            if (isStatic || isPrivate || isFinal || isSynchronized || isNative || isStrictfp || isDefault) {
                return "Annotation method has illegal modifiers";
            }
            if (method.getParameterList().getParametersCount() != 0) {
                return "Annotation method cannot declare parameters";
            }
        }

        if (containingClass.isInterface() && !containingClass.isAnnotationType()) {
            if (method.hasModifierProperty(PsiModifier.PROTECTED) || isFinal) {
                return "Interface method has illegal modifiers";
            }
            if (isDefault && method.getBody() == null) {
                return "Default interface method has no body";
            }
            if ((isPrivate || isStatic) && method.getBody() == null) {
                return "Private or static interface method has no body";
            }
            if (!isDefault && !isPrivate && !isStatic && method.getBody() == null && !isAbstract) {
                return "Interface declaration is missing abstract modifier";
            }
        }

        return null;
    }

    private static String validateReturnType(PsiMethod method, PsiClass containingClass) {
        PsiType returnType = method.getReturnType();

        if (method.isConstructor()) {
            return returnType != null ? "Constructor should not declare a return type" : null;
        }

        if (returnType == null) {
            return "Method return type is missing";
        }

        if (!isTypeResolvable(returnType)) {
            return "Method return type cannot be resolved";
        }

        if (containingClass.isAnnotationType() && !isAnnotationReturnTypeAllowed(returnType)) {
            return "Annotation method return type is illegal";
        }

        return null;
    }

    private static String validateParameterList(PsiMethod method) {
        PsiParameter[] parameters = method.getParameterList().getParameters();
        Set<String> names = new HashSet<>();

        for (int i = 0; i < parameters.length; i++) {
            PsiParameter parameter = parameters[i];
            if (parameter == null || !parameter.isValid()) {
                return "Parameter node is invalid";
            }

            String name = parameter.getName();
            PsiNameHelper nameHelper = PsiNameHelper.getInstance(method.getProject());
            if (!nameHelper.isIdentifier(name)) {
                return "Parameter name is invalid";
            }

            if (!names.add(name)) {
                return "Duplicate parameter name";
            }

            PsiType type = parameter.getType();
            if (type instanceof PsiPrimitiveType && "void".equals(type.getCanonicalText())) {
                return "Parameter type cannot be void";
            }

            if (!isTypeResolvable(type)) {
                return "Parameter type cannot be resolved";
            }

            if (parameter.isVarArgs() && i != parameters.length - 1) {
                return "Varargs parameter must be the last parameter";
            }
        }

        return null;
    }

    private static String validateTypeParameter(PsiMethod method) {
        PsiTypeParameter[] typeParameters = method.getTypeParameters();
        Set<String> names = new HashSet<>();

        for (PsiTypeParameter typeParameter : typeParameters) {
            if (typeParameter == null || !typeParameter.isValid()) {
                return "Type parameter node is invalid";
            }
            String name = typeParameter.getName();
            PsiNameHelper nameHelper = PsiNameHelper.getInstance(method.getProject());
            if (name == null || !nameHelper.isIdentifier(name)) {
                return "Type parameter name is invalid";
            }
            if (!names.add(name)) {
                return "Duplicate type parameter name";
            }

            for (PsiClassType bound : typeParameter.getExtendsListTypes()) {
                if (!isTypeResolvable(bound)) {
                    return "Type parameter bound cannot be resolved";
                }
            }
        }
        return null;
    }

    private static String validateThrowsList(PsiMethod method) {
        PsiClassType[] thrownTypes = method.getThrowsList().getReferencedTypes();
        Set<String> names = new HashSet<>();

        PsiClass throwableClass = JavaPsiFacade.getInstance(method.getProject())
                .findClass(CommonClassNames.JAVA_LANG_THROWABLE, GlobalSearchScope.allScope(method.getProject()));

        for (PsiClassType thrownType : thrownTypes) {
            if (!isTypeResolvable(thrownType)) {
                return "Throws type cannot be resolved";
            }
            PsiClass resolved = thrownType.resolve();
            if (throwableClass != null && resolved != null && !resolved.isInheritor(throwableClass, true)) {
                return "Throws declaration is not a Throwable subtype";
            }
            String name = thrownType.getCanonicalText();
            if (!names.add(name)) {
                return "Duplicate throws declaration";
            }
        }

        return null;
    }

    private static String validateMethodBody(PsiMethod method, PsiClass containingClass) {
        PsiCodeBlock body = method.getBody();
        boolean isAbstract = method.hasModifierProperty(PsiModifier.ABSTRACT);
        boolean isNative = method.hasModifierProperty(PsiModifier.NATIVE);
        boolean isDefault = method.hasModifierProperty(PsiModifier.DEFAULT);
        boolean isPrivate = method.hasModifierProperty(PsiModifier.PRIVATE);
        boolean isStatic = method.hasModifierProperty(PsiModifier.STATIC);

        if (isAbstract && body != null) {
            return "Abstract method should not declare a body";
        }

        if (containingClass.isAnnotationType() && body != null) {
            return "Annotation method should not declare a body";
        }

        if (isNative && body != null) {
            return "Native method should not declare a body";
        }

        if (containingClass.isInterface() && !containingClass.isAnnotationType()) {
            if ((isDefault || isPrivate || isStatic) && body == null) {
                return "Interface method body is missing";
            }
        }

        if (!containingClass.isInterface() && !isAbstract && !isNative && body == null) {
            return "Concrete method body is missing";
        }

        return null;
    }

    private static String validateOverride(PsiMethod method) {
        if (AnnotationUtil.isAnnotated(method, CommonClassNames.JAVA_LANG_OVERRIDE, 0)
                && method.findSuperMethods().length == 0) {
            return "@Override does not actually override any method";
        }
        return null;
    }

    private static String validateUniqueSignature(PsiMethod method, PsiClass containingClass) {
        PsiMethod[] methods = containingClass.findMethodsByName(method.getName(), false);
        for (PsiMethod other : methods) {
            if (other == method) {
                continue;
            }
            if (MethodSignatureUtil.areSignaturesEqual(method, other)) {
                return "Method signature conflicts with another method in the same class";
            }
        }
        return null;
    }

    private static String validatePsiReferences(PsiMethod method) {
        if (!PsiTreeUtil.findChildrenOfType(method, PsiErrorElement.class).isEmpty()) {
            return "Method contains syntax errors";
        }

        Collection<PsiMethodCallExpression> calls = PsiTreeUtil.findChildrenOfType(method, PsiMethodCallExpression.class);
        for (PsiMethodCallExpression call : calls) {
            if (call != null && call.resolveMethod() == null) {
                return "Method contains an unresolved method call";
            }
        }

        Collection<PsiJavaCodeReferenceElement> typeRefs = PsiTreeUtil.findChildrenOfType(method, PsiJavaCodeReferenceElement.class);
        for (PsiJavaCodeReferenceElement typeRef : typeRefs) {
            if (typeRef != null && typeRef.resolve() == null) {
                return "Method contains an unresolved type reference";
            }
        }

        Collection<PsiReferenceExpression> refs = PsiTreeUtil.findChildrenOfType(method, PsiReferenceExpression.class);
        for (PsiReferenceExpression ref : refs) {
            if (ref == null) {
                continue;
            }
            PsiElement resolved = ref.resolve();
            if (resolved == null) {
                return "Method contains an unresolved reference";
            }
        }

        return null;
    }

    private static String validateReturnStatements(PsiMethod method) {
        PsiCodeBlock body = method.getBody();
        if (body == null) {
            return null;
        }

        PsiType returnType = method.getReturnType();
        if (returnType == null || (returnType instanceof PsiPrimitiveType && "void".equals(returnType.getCanonicalText()))) {
            return null;
        }

        Collection<PsiReturnStatement> returns = PsiTreeUtil.findChildrenOfType(method, PsiReturnStatement.class)
                .stream()
                .filter(rs -> isReturnOwnedByMethod(method, rs))
                .toList();
        if (returns.isEmpty()) {
            Collection<PsiThrowStatement> throwsStmts = PsiTreeUtil.findChildrenOfType(method, PsiThrowStatement.class);
            if (throwsStmts.isEmpty()) {
                return "Non-void method has neither return nor throw statement";
            }
            return null;
        }

        for (PsiReturnStatement rs : returns) {
            if (rs == null) {
                continue;
            }
            PsiExpression rv = rs.getReturnValue();
            if (rv == null) {
                return "Non-void method contains an empty return statement";
            }
        }

        return null;
    }

    private static boolean isReturnOwnedByMethod(PsiMethod method, PsiReturnStatement rs) {
        PsiMethod ownerMethod = PsiTreeUtil.getParentOfType(rs, PsiMethod.class);
        if (ownerMethod != method) {
            return false;
        }

        PsiLambdaExpression lambdaOwner = PsiTreeUtil.getParentOfType(rs, PsiLambdaExpression.class);
        return lambdaOwner == null;
    }

    private static boolean isTypeResolvable(PsiType type) {
        if (type == null) {
            return false;
        }
        if (type instanceof PsiArrayType psiArrayType) {
            return isTypeResolvable(psiArrayType.getComponentType());
        }
        if (type instanceof PsiClassType psiClassType) {
            return psiClassType.resolve() != null;
        }
        return true;
    }

    private static boolean isAnnotationReturnTypeAllowed(PsiType returnType) {
        if (returnType instanceof PsiArrayType arrayType) {
            return isAnnotationReturnTypeAllowed(arrayType.getComponentType());
        }

        if (returnType instanceof PsiPrimitiveType) {
            return true;
        }

        if (returnType instanceof PsiClassType classType) {
            PsiClass resolved = classType.resolve();
            if (resolved == null) {
                return false;
            }
            String qName = resolved.getQualifiedName();
            if (CommonClassNames.JAVA_LANG_STRING.equals(qName) || CommonClassNames.JAVA_LANG_CLASS.equals(qName)) {
                return true;
            }
            return resolved.isEnum() || resolved.isAnnotationType();
        }

        return false;
    }
}
