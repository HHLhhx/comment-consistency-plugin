package com.nju.comment.util;

import com.intellij.codeInsight.AnnotationUtil;
import com.intellij.openapi.project.Project;
import com.intellij.codeInsight.ExceptionUtil;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.MethodSignatureUtil;
import com.intellij.psi.util.PsiTreeUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * PsiMethod 合法性校验工具。
 * <p>
 * 目标：尽可能覆盖 Java 编译期会报错的场景，避免对不完整/错误的方法做进一步处理。
 * 该校验不追求 100% 语义级正确性（如完整控制流返回分析），但会对结构、修饰符、类型、
 * 引用解析、方法体可用性等关键点进行严格验证。
 */
@Slf4j
public final class MethodValidationUtil {

    private MethodValidationUtil() {
    }

    /**
     * 判断方法是否“可编译”。
     *
     * @param method 目标方法
     * @return 是否合法
     */
    public static boolean isValid(PsiMethod method) {
        if (method == null || !method.isValid()) {
            log.warn("方法为空或 PSI 已失效，无法处理: {}", method);
            return false;
        }

        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null || !containingClass.isValid()) {
            log.warn("方法未归属到有效类中，跳过：{}", MethodRecordUtil.buildMethodKey(method));
            return false;
        }

        if (!isMethodNameValid(method, containingClass)) {
            return false;
        }

        if (!isModifierValid(method, containingClass)) {
            return false;
        }

        if (!isReturnTypeValid(method, containingClass)) {
            return false;
        }

        if (!isParameterListValid(method)) {
            return false;
        }

        if (!isTypeParameterValid(method)) {
            return false;
        }

        if (!isThrowsListValid(method)) {
            return false;
        }

        if (!isMethodBodyValid(method, containingClass)) {
            return false;
        }

        if (!areCheckedExceptionsHandledOrDeclared(method)) {
            return false;
        }

        if (!isOverrideValid(method)) {
            return false;
        }

        if (!isSignatureUniqueInClass(method, containingClass)) {
            return false;
        }

        if (!isPsiReferencesResolved(method)) {
            return false;
        }

        if (!isReturnStatementValid(method)) {
            return false;
        }

        return true;
    }

    /**
     * 检查方法体中的检查型异常（非 RuntimeException/Error）是否被捕获或在 throws 中声明。
     * 近似规则：
     * - 通过 ExceptionUtil 计算未被 catch 的异常集合；
     * - 排除未检查异常（RuntimeException/Error 及其子类）；
     * - 若未检查异常不在 throws 列表（或其父类型）中，则判定为非法。
     */
    private static boolean areCheckedExceptionsHandledOrDeclared(PsiMethod method) {
        String methodKey = MethodRecordUtil.buildMethodKey(method);
        PsiCodeBlock body = method.getBody();
        if (body == null) {
            // 抽象/接口无方法体或不需要方法体的情况，跳过该检查
            return true;
        }

        // 计算在该方法体作用域内未被捕获的异常类型
        final PsiClassType[] unhandled = ExceptionUtil.getUnhandledExceptions(body).toArray(new PsiClassType[0]);
        if (unhandled.length == 0) {
            return true;
        }

        // 已声明的 throws 类型
        PsiClassType[] declared = method.getThrowsList().getReferencedTypes();

        // 基类：RuntimeException 与 Error（未检查异常）
        Project project = method.getProject();
        PsiClass runtimeEx = JavaPsiFacade.getInstance(project)
                .findClass(CommonClassNames.JAVA_LANG_RUNTIME_EXCEPTION, GlobalSearchScope.allScope(project));
        PsiClass errorEx = JavaPsiFacade.getInstance(project)
                .findClass(CommonClassNames.JAVA_LANG_ERROR, GlobalSearchScope.allScope(project));

        for (PsiClassType type : unhandled) {
            PsiClass exCls = type.resolve();
            if (exCls == null) {
                // 无法解析，按严格策略视为未满足
                log.warn("存在未解析异常类型，且未捕获/未声明，跳过：{} -> {}", methodKey, type.getCanonicalText());
                return false;
            }

            // 跳过未检查异常（RuntimeException 与 Error 及其子类）
            boolean isUnchecked = (runtimeEx != null && exCls.isInheritor(runtimeEx, true))
                    || (errorEx != null && exCls.isInheritor(errorEx, true))
                    || CommonClassNames.JAVA_LANG_RUNTIME_EXCEPTION.equals(exCls.getQualifiedName())
                    || CommonClassNames.JAVA_LANG_ERROR.equals(exCls.getQualifiedName());
            if (isUnchecked) {
                continue;
            }

            // 检查是否在 throws 声明中被覆盖（父类型也可覆盖子类型）
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
                log.warn("方法包含未被捕获且未在 throws 声明的检查型异常，跳过：{} -> {}", methodKey, type.getCanonicalText());
                return false;
            }
        }

        return true;
    }

    /**
     * 方法名合法性校验：构造器名称必须与类名一致；普通方法名必须是合法标识符。
     */
    private static boolean isMethodNameValid(PsiMethod method, PsiClass containingClass) {
        String methodKey = MethodRecordUtil.buildMethodKey(method);
        if (method.getNameIdentifier() == null) {
            log.warn("方法缺少名称标识符，跳过：{}", methodKey);
            return false;
        }

        String name = method.getName();
        Project project = method.getProject();
        PsiNameHelper nameHelper = PsiNameHelper.getInstance(project);

        if (method.isConstructor()) {
            if (!Objects.equals(containingClass.getName(), name)) {
                log.warn("构造器名称与类名不一致，跳过：{}", methodKey);
                return false;
            }
        } else {
            if (!nameHelper.isIdentifier(name)) {
                log.warn("方法名不是合法 Java 标识符，跳过：{} -> {}", methodKey, name);
                return false;
            }
        }
        return true;
    }

    /**
     * 修饰符合法性校验：互斥组合、接口/注解/构造器等特殊规则。
     */
    private static boolean isModifierValid(PsiMethod method, PsiClass containingClass) {
        String methodKey = MethodRecordUtil.buildMethodKey(method);
        boolean isAbstract = method.hasModifierProperty(PsiModifier.ABSTRACT);
        boolean isFinal = method.hasModifierProperty(PsiModifier.FINAL);
        boolean isPrivate = method.hasModifierProperty(PsiModifier.PRIVATE);
        boolean isStatic = method.hasModifierProperty(PsiModifier.STATIC);
        boolean isNative = method.hasModifierProperty(PsiModifier.NATIVE);
        boolean isSynchronized = method.hasModifierProperty(PsiModifier.SYNCHRONIZED);
        boolean isStrictfp = method.hasModifierProperty(PsiModifier.STRICTFP);
        boolean isDefault = method.hasModifierProperty(PsiModifier.DEFAULT);

        boolean isPublic = method.hasModifierProperty(PsiModifier.PUBLIC);

        System.out.println("isPublic: " + isPublic
                + ", isAbstract: " + isAbstract
                + ", isFinal: " + isFinal
                + ", isPrivate: " + isPrivate
                + ", isStatic: " + isStatic
                + ", isNative: " + isNative
                + ", isSynchronized: " + isSynchronized
                + ", isStrictfp: " + isStrictfp
                + ", isDefault: " + isDefault
                + ", methodKey: " + methodKey
                + ", hasBody: " + (method.getBody() != null));

        // 抽象方法与多种修饰符互斥
        if (isAbstract && (isFinal || isPrivate || isStatic || isNative || isSynchronized || isStrictfp || isDefault)) {
            log.warn("抽象方法存在互斥修饰符，跳过：{}", methodKey);
            return false;
        }

        // 构造器修饰符限制
        if (method.isConstructor()) {
            if (isAbstract || isFinal || isStatic || isNative || isSynchronized || isStrictfp) {
                log.warn("构造器包含非法修饰符，跳过：{}", methodKey);
                return false;
            }
        }

        // 注解类型成员方法规则
        if (containingClass.isAnnotationType()) {
            if (isStatic || isPrivate || isFinal || isSynchronized || isNative || isStrictfp || isDefault) {
                log.warn("注解方法存在非法修饰符，跳过：{}", methodKey);
                return false;
            }
            if (method.getParameterList().getParametersCount() != 0) {
                log.warn("注解方法不允许有参数，跳过：{}", methodKey);
                return false;
            }
        }

        // 接口方法规则
        if (containingClass.isInterface() && !containingClass.isAnnotationType()) {
            System.out.println(methodKey);
            // 接口方法不允许 protected/final
            if (method.hasModifierProperty(PsiModifier.PROTECTED) || isFinal) {
                log.warn("接口方法包含非法修饰符，跳过：{}", methodKey);
                return false;
            }
            // default 方法必须有方法体
            if (isDefault && method.getBody() == null) {
                log.warn("接口 default 方法缺少方法体，跳过：{}", methodKey);
                return false;
            }
            // private/static 方法必须有方法体
            if ((isPrivate || isStatic) && method.getBody() == null) {
                log.warn("接口 private/static 方法缺少方法体，跳过：{}", methodKey);
                return false;
            }
            // 非 default/private/static 的接口方法若无方法体，则必须是 abstract
            if (!isDefault && !isPrivate && !isStatic && method.getBody() == null && !isAbstract) {
                log.warn("接口抽象方法缺少 abstract 修饰符，跳过：{}", methodKey);
                return false;
            }
        }

        return true;
    }

    /**
     * 返回类型合法性校验：构造器无返回类型；普通方法返回类型必须可解析。
     * 注解方法返回类型有限制。
     */
    private static boolean isReturnTypeValid(PsiMethod method, PsiClass containingClass) {
        String methodKey = MethodRecordUtil.buildMethodKey(method);
        PsiType returnType = method.getReturnType();

        if (method.isConstructor()) {
            if (returnType != null) {
                log.warn("构造器不应有返回类型，跳过：{}", methodKey);
                return false;
            }
            return true;
        }

        if (returnType == null) {
            log.warn("方法缺少返回类型，跳过：{}", methodKey);
            return false;
        }

        if (!isTypeResolvable(returnType)) {
            log.warn("方法返回类型不可解析，跳过：{} -> {}", methodKey, returnType.getCanonicalText());
            return false;
        }

        if (containingClass.isAnnotationType() && !isAnnotationReturnTypeAllowed(returnType)) {
            log.warn("注解方法返回类型非法，跳过：{} -> {}", methodKey, returnType.getCanonicalText());
            return false;
        }

        return true;
    }

    /**
     * 参数列表合法性校验：类型可解析、名称合法、不重复、varargs 位置正确。
     */
    private static boolean isParameterListValid(PsiMethod method) {
        String methodKey = MethodRecordUtil.buildMethodKey(method);
        PsiParameter[] parameters = method.getParameterList().getParameters();
        Set<String> names = new HashSet<>();

        for (int i = 0; i < parameters.length; i++) {
            PsiParameter parameter = parameters[i];
            if (parameter == null || !parameter.isValid()) {
                log.warn("存在无效参数节点，跳过：{}", methodKey);
                return false;
            }

            String name = parameter.getName();
            PsiNameHelper nameHelper = PsiNameHelper.getInstance(method.getProject());
            if (!nameHelper.isIdentifier(name)) {
                log.warn("参数名非法，跳过：{} -> {}", methodKey, name);
                return false;
            }

            if (!names.add(name)) {
                log.warn("参数名重复，跳过：{} -> {}", methodKey, name);
                return false;
            }

            PsiType type = parameter.getType();
            if (type instanceof PsiPrimitiveType && "void".equals(type.getCanonicalText())) {
                log.warn("参数类型不能为 void，跳过：{} -> {}", methodKey, name);
                return false;
            }

            if (!isTypeResolvable(type)) {
                log.warn("参数类型不可解析，跳过：{} -> {}", methodKey, type.getCanonicalText());
                return false;
            }

            if (parameter.isVarArgs() && i != parameters.length - 1) {
                log.warn("可变参数必须是最后一个，跳过：{}", methodKey);
                return false;
            }
        }

        return true;
    }

    /**
     * 类型参数合法性校验：名称合法、不重复、上界可解析。
     */
    private static boolean isTypeParameterValid(PsiMethod method) {
        String methodKey = MethodRecordUtil.buildMethodKey(method);
        PsiTypeParameter[] typeParameters = method.getTypeParameters();
        Set<String> names = new HashSet<>();

        for (PsiTypeParameter typeParameter : typeParameters) {
            if (typeParameter == null || !typeParameter.isValid()) {
                log.warn("类型参数节点无效，跳过：{}", methodKey);
                return false;
            }
            String name = typeParameter.getName();
            PsiNameHelper nameHelper = PsiNameHelper.getInstance(method.getProject());
            if (name == null || !nameHelper.isIdentifier(name)) {
                log.warn("类型参数名非法，跳过：{} -> {}", methodKey, name);
                return false;
            }
            if (!names.add(name)) {
                log.warn("类型参数名重复，跳过：{} -> {}", methodKey, name);
                return false;
            }

            for (PsiClassType bound : typeParameter.getExtendsListTypes()) {
                if (!isTypeResolvable(bound)) {
                    log.warn("类型参数上界不可解析，跳过：{} -> {}", methodKey, bound.getCanonicalText());
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * throws 子句合法性校验：必须是 Throwable 子类、可解析且不重复。
     */
    private static boolean isThrowsListValid(PsiMethod method) {
        String methodKey = MethodRecordUtil.buildMethodKey(method);
        PsiClassType[] thrownTypes = method.getThrowsList().getReferencedTypes();
        Set<String> names = new HashSet<>();

        PsiClass throwableClass = JavaPsiFacade.getInstance(method.getProject())
                .findClass(CommonClassNames.JAVA_LANG_THROWABLE, GlobalSearchScope.allScope(method.getProject()));

        for (PsiClassType thrownType : thrownTypes) {
            if (!isTypeResolvable(thrownType)) {
                log.warn("throws 类型不可解析，跳过：{} -> {}", methodKey, thrownType.getCanonicalText());
                return false;
            }
            PsiClass resolved = thrownType.resolve();
            if (throwableClass != null && resolved != null && !resolved.isInheritor(throwableClass, true)) {
                log.warn("throws 类型不是 Throwable 子类，跳过：{} -> {}", methodKey, thrownType.getCanonicalText());
                return false;
            }
            String name = thrownType.getCanonicalText();
            if (!names.add(name)) {
                log.warn("throws 类型重复，跳过：{} -> {}", methodKey, name);
                return false;
            }
        }

        return true;
    }

    /**
     * 方法体合法性校验：抽象/注解/native 等情况不允许方法体；需要方法体的必须存在。
     */
    private static boolean isMethodBodyValid(PsiMethod method, PsiClass containingClass) {
        String methodKey = MethodRecordUtil.buildMethodKey(method);
        PsiCodeBlock body = method.getBody();
        boolean isAbstract = method.hasModifierProperty(PsiModifier.ABSTRACT);
        boolean isNative = method.hasModifierProperty(PsiModifier.NATIVE);
        boolean isDefault = method.hasModifierProperty(PsiModifier.DEFAULT);
        boolean isPrivate = method.hasModifierProperty(PsiModifier.PRIVATE);
        boolean isStatic = method.hasModifierProperty(PsiModifier.STATIC);

        if (isAbstract && body != null) {
            log.warn("抽象方法不允许包含方法体，跳过：{}", methodKey);
            return false;
        }

        if (containingClass.isAnnotationType() && body != null) {
            log.warn("注解方法不允许包含方法体，跳过：{}", methodKey);
            return false;
        }

        if (isNative && body != null) {
            log.warn("native 方法不允许包含方法体，跳过：{}", methodKey);
            return false;
        }

        // 接口 default/private/static 方法必须有方法体
        if (containingClass.isInterface() && !containingClass.isAnnotationType()) {
            if ((isDefault || isPrivate || isStatic) && body == null) {
                log.warn("接口 default/private/static 方法缺少方法体，跳过：{}", methodKey);
                return false;
            }
        }

        // 普通类中非抽象、非 native 方法必须有方法体
        if (!containingClass.isInterface() && !isAbstract && !isNative && body == null) {
            log.warn("非抽象方法缺少方法体，跳过：{}", methodKey);
            return false;
        }

        return true;
    }

    /**
     * &#064;Override  注解合法性校验：如果标注 @Override，必须实际覆盖父类/接口方法。
     */
    private static boolean isOverrideValid(PsiMethod method) {
        String methodKey = MethodRecordUtil.buildMethodKey(method);
        if (AnnotationUtil.isAnnotated(method, CommonClassNames.JAVA_LANG_OVERRIDE, 0)) {
            PsiMethod[] superMethods = method.findSuperMethods();
            if (superMethods.length == 0) {
                log.warn("@Override 未覆盖任何方法，跳过：{}", methodKey);
                return false;
            }
        }
        return true;
    }

    /**
     * 校验类内方法签名唯一性，避免与同类方法签名冲突。
     */
    private static boolean isSignatureUniqueInClass(PsiMethod method, PsiClass containingClass) {
        String methodKey = MethodRecordUtil.buildMethodKey(method);
        PsiMethod[] methods = containingClass.findMethodsByName(method.getName(), false);
        for (PsiMethod other : methods) {
            if (other == method) {
                continue;
            }
            if (MethodSignatureUtil.areSignaturesEqual(method, other)) {
                log.warn("方法签名与同类方法冲突，跳过：{}", methodKey);
                return false;
            }
        }
        return true;
    }

    /**
     * PSI 引用解析校验：方法调用、类型引用、一般引用必须可解析。
     */
    private static boolean isPsiReferencesResolved(PsiMethod method) {
        String methodKey = MethodRecordUtil.buildMethodKey(method);
        if (!PsiTreeUtil.findChildrenOfType(method, PsiErrorElement.class).isEmpty()) {
            log.warn("方法存在语法错误，跳过：{}", methodKey);
            return false;
        }

        Collection<PsiMethodCallExpression> calls = PsiTreeUtil.findChildrenOfType(method, PsiMethodCallExpression.class);
        for (PsiMethodCallExpression call : calls) {
            if (call == null) {
                continue;
            }
            if (call.resolveMethod() == null) {
                log.warn("方法存在未解析的方法调用，跳过：{}", methodKey);
                return false;
            }
        }

        Collection<PsiJavaCodeReferenceElement> typeRefs = PsiTreeUtil.findChildrenOfType(method, PsiJavaCodeReferenceElement.class);
        for (PsiJavaCodeReferenceElement typeRef : typeRefs) {
            if (typeRef == null) {
                continue;
            }
            if (typeRef.resolve() == null) {
                log.warn("方法存在未解析的类型引用，跳过：{}", methodKey);
                return false;
            }
        }

        Collection<PsiReferenceExpression> refs = PsiTreeUtil.findChildrenOfType(method, PsiReferenceExpression.class);
        for (PsiReferenceExpression ref : refs) {
            if (ref == null) {
                continue;
            }
            PsiElement resolved = ref.resolve();
            if (resolved == null) {
                log.warn("方法包含未解析的引用，跳过：{} -> {}", methodKey, ref.getText());
                return false;
            }
        }

        return true;
    }

    /**
     * 非 void 方法的 return 校验：缺少 return 且无 throw 的情况视为非法。
     * 该校验为“近似控制流”判断，避免明显缺失返回语句的错误。
     */
    private static boolean isReturnStatementValid(PsiMethod method) {
        String methodKey = MethodRecordUtil.buildMethodKey(method);
        PsiType returnType = method.getReturnType();
        if (returnType == null || (returnType instanceof PsiPrimitiveType && "void".equals(returnType.getCanonicalText()))) {
            return true;
        }

        Collection<PsiReturnStatement> returns = PsiTreeUtil.findChildrenOfType(method, PsiReturnStatement.class);
        if (returns.isEmpty()) {
            Collection<PsiThrowStatement> throwsStmts = PsiTreeUtil.findChildrenOfType(method, PsiThrowStatement.class);
            if (throwsStmts.isEmpty()) {
                log.warn("非 void 方法缺少 return/throw，跳过：{}", methodKey);
                return false;
            }
            return true;
        }

        for (PsiReturnStatement rs : returns) {
            if (rs == null) {
                continue;
            }
            PsiExpression rv = rs.getReturnValue();
            if (rv == null) {
                log.warn("非 void 方法存在空 return 语句，跳过：{}", methodKey);
                return false;
            }
        }

        return true;
    }

    /**
     * 判断类型是否可解析。原始类型与数组类型直接通过；类类型必须能 resolve。
     */
    private static boolean isTypeResolvable(PsiType type) {
        return switch (type) {
            case null -> false;
            case PsiArrayType psiArrayType -> isTypeResolvable(psiArrayType.getComponentType());
            case PsiClassType psiClassType -> psiClassType.resolve() != null;
            default -> true; // 原始类型等直接视为可解析
        };
    }

    /**
     * 注解方法返回类型允许范围校验。
     */
    private static boolean isAnnotationReturnTypeAllowed(PsiType returnType) {
        if (returnType instanceof PsiArrayType) {
            // 注解允许一维数组
            PsiType component = ((PsiArrayType) returnType).getComponentType();
            return isAnnotationReturnTypeAllowed(component);
        }

        if (returnType instanceof PsiPrimitiveType) {
            return true;
        }

        if (returnType instanceof PsiClassType) {
            PsiClass resolved = ((PsiClassType) returnType).resolve();
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
