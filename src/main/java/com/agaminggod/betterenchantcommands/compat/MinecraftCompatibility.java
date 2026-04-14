package com.agaminggod.betterenchantcommands.compat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

public final class MinecraftCompatibility {
    private static final ConcurrentMap<Class<?>, PermissionAccess> PERMISSION_ACCESS_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, WithPermissionAccess> WITH_PERMISSION_ACCESS_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, ComponentReadAccess> COMPONENT_READ_ACCESS_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, RegistryLookupAccess> REGISTRY_LOOKUP_ACCESS_CACHE = new ConcurrentHashMap<>();

    private MinecraftCompatibility() {
    }

    public static boolean hasPermissionLevel(final CommandSourceStack source, final int requiredLevel) {
        try {
            return PERMISSION_ACCESS_CACHE.computeIfAbsent(source.getClass(), MinecraftCompatibility::resolvePermissionAccess)
                .hasPermission(source, requiredLevel);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to evaluate command permission compatibility", exception);
        }
    }

    public static CommandSourceStack withPermissionLevel(final CommandSourceStack source, final int requiredLevel) {
        try {
            return WITH_PERMISSION_ACCESS_CACHE.computeIfAbsent(source.getClass(), MinecraftCompatibility::resolveWithPermissionAccess)
                .apply(source, requiredLevel);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to elevate command source permission compatibility", exception);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T getComponentOrDefault(final ItemStack stack, final Object componentType, final T fallbackValue) {
        try {
            return (T) COMPONENT_READ_ACCESS_CACHE.computeIfAbsent(stack.getClass(), MinecraftCompatibility::resolveComponentReadAccess)
                .read(stack, componentType, fallbackValue);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to read item component compatibility", exception);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> Holder<T> findRegistryHolderById(final Registry<T> registry, final Identifier id) {
        try {
            final Object resolvedHolder = REGISTRY_LOOKUP_ACCESS_CACHE.computeIfAbsent(
                registry.getClass(),
                MinecraftCompatibility::resolveRegistryLookupAccess
            ).findHolder(registry, id);

            if (resolvedHolder == null) {
                return null;
            }

            return (Holder<T>) resolvedHolder;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to resolve registry holder compatibility for " + id, exception);
        }
    }

    private static PermissionAccess resolvePermissionAccess(final Class<?> sourceClass) {
        final Method legacyPermissionMethod = findInstanceMethod(
            sourceClass,
            boolean.class,
            int.class
        );
        if (legacyPermissionMethod != null) {
            legacyPermissionMethod.setAccessible(true);
            return new PermissionAccess(legacyPermissionMethod, null, null, null);
        }

        for (Method candidate : sourceClass.getMethods()) {
            if (Modifier.isStatic(candidate.getModifiers()) || candidate.getParameterCount() != 0 || candidate.getReturnType().isPrimitive()) {
                continue;
            }

            final PermissionLevelAccess levelAccess = resolvePermissionLevelAccess(candidate.getReturnType());
            if (levelAccess == null) {
                continue;
            }

            candidate.setAccessible(true);
            return new PermissionAccess(null, candidate, levelAccess.levelGetter(), levelAccess.levelIdGetter());
        }

        throw new IllegalStateException("Unsupported permission model for command source class " + sourceClass.getName());
    }

    private static WithPermissionAccess resolveWithPermissionAccess(final Class<?> sourceClass) {
        for (Method candidate : sourceClass.getMethods()) {
            if (Modifier.isStatic(candidate.getModifiers())
                || candidate.getParameterCount() != 1
                || !sourceClass.isAssignableFrom(candidate.getReturnType())) {
                continue;
            }

            candidate.setAccessible(true);
            final Class<?> parameterType = candidate.getParameterTypes()[0];
            if (parameterType == int.class) {
                return new WithPermissionAccess(candidate, null);
            }

            final PermissionSetFactory factory = resolvePermissionSetFactory(parameterType);
            if (factory != null) {
                return new WithPermissionAccess(candidate, factory);
            }
        }

        throw new IllegalStateException("Unsupported withPermission model for command source class " + sourceClass.getName());
    }

    private static PermissionLevelAccess resolvePermissionLevelAccess(final Class<?> permissionContainerClass) {
        for (Class<?> candidateType : collectTypeHierarchy(permissionContainerClass)) {
            for (Method candidate : candidateType.getMethods()) {
                if (Modifier.isStatic(candidate.getModifiers()) || candidate.getParameterCount() != 0 || candidate.getReturnType().isPrimitive()) {
                    continue;
                }

                final Method levelIdGetter = findNoArgIntMethod(candidate.getReturnType());
                if (levelIdGetter == null) {
                    continue;
                }

                candidate.setAccessible(true);
                levelIdGetter.setAccessible(true);
                return new PermissionLevelAccess(candidate, levelIdGetter);
            }
        }

        return null;
    }

    private static PermissionSetFactory resolvePermissionSetFactory(final Class<?> permissionSetType) {
        for (Class<?> candidateType : collectTypeHierarchy(permissionSetType)) {
            for (Method factoryCandidate : candidateType.getMethods()) {
                if (!Modifier.isStatic(factoryCandidate.getModifiers())
                    || factoryCandidate.getParameterCount() != 1
                    || !permissionSetType.isAssignableFrom(factoryCandidate.getReturnType())) {
                    continue;
                }

                final Class<?> levelType = factoryCandidate.getParameterTypes()[0];
                final Method levelById = findStaticIntFactory(levelType);
                if (levelById == null) {
                    continue;
                }

                factoryCandidate.setAccessible(true);
                levelById.setAccessible(true);
                return new PermissionSetFactory(factoryCandidate, levelById);
            }
        }

        return null;
    }

    private static ComponentReadAccess resolveComponentReadAccess(final Class<?> stackClass) {
        final List<Method> twoArgMethods = new ArrayList<>();
        final List<Method> oneArgMethods = new ArrayList<>();

        for (Method candidate : stackClass.getMethods()) {
            if (Modifier.isStatic(candidate.getModifiers())
                || candidate.getReturnType() == void.class) {
                continue;
            }

            if (candidate.getParameterCount() == 2
                && !candidate.getParameterTypes()[0].isPrimitive()
                && !candidate.getParameterTypes()[1].isPrimitive()) {
                candidate.setAccessible(true);
                twoArgMethods.add(candidate);
            } else if (candidate.getParameterCount() == 1
                && candidate.getReturnType() != boolean.class
                && !candidate.getParameterTypes()[0].isPrimitive()) {
                candidate.setAccessible(true);
                oneArgMethods.add(candidate);
            }
        }

        if (twoArgMethods.isEmpty() && oneArgMethods.isEmpty()) {
            throw new IllegalStateException("Unsupported component read model for item stack class " + stackClass.getName());
        }

        return new ComponentReadAccess(List.copyOf(twoArgMethods), List.copyOf(oneArgMethods));
    }

    private static RegistryLookupAccess resolveRegistryLookupAccess(final Class<?> registryClass) {
        final List<Method> optionalLookupMethods = new ArrayList<>();
        final List<Method> holderWrapMethods = new ArrayList<>();

        for (Method candidate : registryClass.getMethods()) {
            if (Modifier.isStatic(candidate.getModifiers()) || candidate.getParameterCount() != 1) {
                continue;
            }

            if (Optional.class.equals(candidate.getReturnType()) && !candidate.getParameterTypes()[0].isPrimitive()) {
                candidate.setAccessible(true);
                optionalLookupMethods.add(candidate);
                continue;
            }

            if (!candidate.getReturnType().isPrimitive()) {
                candidate.setAccessible(true);
                holderWrapMethods.add(candidate);
            }
        }

        if (optionalLookupMethods.isEmpty()) {
            throw new IllegalStateException("Unsupported registry lookup model for registry class " + registryClass.getName());
        }

        return new RegistryLookupAccess(List.copyOf(optionalLookupMethods), List.copyOf(holderWrapMethods));
    }

    private static Method findInstanceMethod(final Class<?> owner, final Class<?> returnType, final Class<?> parameterType) {
        for (Method candidate : owner.getMethods()) {
            if (!Modifier.isStatic(candidate.getModifiers())
                && candidate.getReturnType() == returnType
                && candidate.getParameterCount() == 1
                && candidate.getParameterTypes()[0] == parameterType) {
                return candidate;
            }
        }

        return null;
    }

    private static Method findNoArgIntMethod(final Class<?> owner) {
        for (Method candidate : owner.getMethods()) {
            if (!Modifier.isStatic(candidate.getModifiers())
                && candidate.getParameterCount() == 0
                && candidate.getReturnType() == int.class) {
                return candidate;
            }
        }

        return null;
    }

    private static Method findStaticIntFactory(final Class<?> owner) {
        for (Method candidate : owner.getMethods()) {
            if (Modifier.isStatic(candidate.getModifiers())
                && candidate.getParameterCount() == 1
                && candidate.getParameterTypes()[0] == int.class
                && candidate.getReturnType() == owner) {
                return candidate;
            }
        }

        return null;
    }

    private static List<Class<?>> collectTypeHierarchy(final Class<?> root) {
        final List<Class<?>> hierarchy = new ArrayList<>();
        Class<?> current = root;
        while (current != null && current != Object.class) {
            hierarchy.add(current);
            for (Class<?> iface : current.getInterfaces()) {
                if (!hierarchy.contains(iface)) {
                    hierarchy.add(iface);
                }
            }
            current = current.getSuperclass();
        }
        return hierarchy;
    }

    private record PermissionAccess(
        Method legacyPermissionMethod,
        Method permissionContainerGetter,
        Method permissionLevelGetter,
        Method permissionLevelIdGetter
    ) {
        boolean hasPermission(final Object source, final int requiredLevel) throws ReflectiveOperationException {
            if (legacyPermissionMethod != null) {
                return (boolean) invoke(legacyPermissionMethod, source, requiredLevel);
            }

            final Object permissionContainer = invoke(permissionContainerGetter, source);
            final Object permissionLevel = invoke(permissionLevelGetter, permissionContainer);
            final int actualLevel = (int) invoke(permissionLevelIdGetter, permissionLevel);
            return actualLevel >= requiredLevel;
        }
    }

    private record PermissionLevelAccess(Method levelGetter, Method levelIdGetter) {
    }

    private record WithPermissionAccess(Method withPermissionMethod, PermissionSetFactory permissionSetFactory) {
        CommandSourceStack apply(final CommandSourceStack source, final int requiredLevel) throws ReflectiveOperationException {
            if (withPermissionMethod.getParameterTypes()[0] == int.class) {
                return (CommandSourceStack) invoke(withPermissionMethod, source, requiredLevel);
            }

            final Object permissionSet = permissionSetFactory.create(requiredLevel);
            return (CommandSourceStack) invoke(withPermissionMethod, source, permissionSet);
        }
    }

    private record PermissionSetFactory(Method permissionSetFactory, Method permissionLevelFactory) {
        Object create(final int requiredLevel) throws ReflectiveOperationException {
            final Object permissionLevel = invoke(permissionLevelFactory, null, requiredLevel);
            return invoke(permissionSetFactory, null, permissionLevel);
        }
    }

    private static final class ComponentReadAccess {
        private final List<Method> twoArgMethods;
        private final List<Method> oneArgMethods;
        private final ConcurrentMap<Class<?>, Method> twoArgByComponentType = new ConcurrentHashMap<>();
        private final ConcurrentMap<Class<?>, Method> oneArgByComponentType = new ConcurrentHashMap<>();

        ComponentReadAccess(final List<Method> twoArgMethods, final List<Method> oneArgMethods) {
            this.twoArgMethods = twoArgMethods;
            this.oneArgMethods = oneArgMethods;
        }

        Object read(final ItemStack stack, final Object componentType, final Object fallbackValue) throws ReflectiveOperationException {
            final Class<?> componentClass = componentType.getClass();

            final Method cachedTwoArg = twoArgByComponentType.get(componentClass);
            if (cachedTwoArg != null) {
                return invoke(cachedTwoArg, stack, componentType, fallbackValue);
            }

            final Method cachedOneArg = oneArgByComponentType.get(componentClass);
            if (cachedOneArg != null) {
                final Object resolved = invoke(cachedOneArg, stack, componentType);
                return resolved == null ? fallbackValue : resolved;
            }

            for (Method method : twoArgMethods) {
                if (method.getParameterTypes()[0].isInstance(componentType)
                    && method.getParameterTypes()[1].isInstance(fallbackValue)) {
                    twoArgByComponentType.putIfAbsent(componentClass, method);
                    return invoke(method, stack, componentType, fallbackValue);
                }
            }

            for (Method method : oneArgMethods) {
                if (method.getParameterTypes()[0].isInstance(componentType)) {
                    oneArgByComponentType.putIfAbsent(componentClass, method);
                    final Object resolved = invoke(method, stack, componentType);
                    return resolved == null ? fallbackValue : resolved;
                }
            }

            throw new IllegalStateException("No compatible component read method found for item stack class " + stack.getClass().getName());
        }
    }

    private static final class RegistryLookupAccess {
        private final List<Method> optionalLookupMethods;
        private final List<Method> holderWrapMethods;
        private final ConcurrentMap<Class<?>, List<Method>> lookupMethodsByIdType = new ConcurrentHashMap<>();

        RegistryLookupAccess(final List<Method> optionalLookupMethods, final List<Method> holderWrapMethods) {
            this.optionalLookupMethods = optionalLookupMethods;
            this.holderWrapMethods = holderWrapMethods;
        }

        Object findHolder(final Object registry, final Object id) throws ReflectiveOperationException {
            final List<Method> applicableLookups = lookupMethodsByIdType.computeIfAbsent(
                id.getClass(),
                idClass -> {
                    final List<Method> matching = new ArrayList<>();
                    for (Method candidate : optionalLookupMethods) {
                        if (candidate.getParameterTypes()[0].isAssignableFrom(idClass)) {
                            matching.add(candidate);
                        }
                    }
                    return List.copyOf(matching);
                }
            );

            Object directValue = null;

            for (Method candidate : applicableLookups) {
                final Optional<?> optional = (Optional<?>) invoke(candidate, registry, id);
                if (optional.isEmpty()) {
                    continue;
                }

                final Object value = optional.get();
                if (value instanceof Holder<?>) {
                    return value;
                }

                if (directValue == null) {
                    directValue = value;
                }
            }

            if (directValue == null) {
                return null;
            }

            for (Method candidate : holderWrapMethods) {
                if (!candidate.getParameterTypes()[0].isInstance(directValue)) {
                    continue;
                }

                final Object wrapped = invoke(candidate, registry, directValue);
                if (wrapped instanceof Holder<?>) {
                    return wrapped;
                }
            }

            @SuppressWarnings("unchecked")
            final Holder<Object> directHolder = Holder.direct(directValue);
            return directHolder;
        }
    }

    private static Object invoke(final Method method, final Object target, final Object... arguments) throws ReflectiveOperationException {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException exception) {
            final Throwable cause = exception.getCause();
            if (cause instanceof ReflectiveOperationException reflectiveOperationException) {
                throw reflectiveOperationException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw new IllegalStateException(buildInvocationError(method) + ": " + runtimeException.getMessage(), runtimeException);
            }

            throw new IllegalStateException(buildInvocationError(method), cause);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(buildInvocationError(method) + " - argument mismatch", exception);
        }
    }

    private static String buildInvocationError(final Method method) {
        final StringBuilder sb = new StringBuilder("Failed compatibility invocation: ");
        sb.append(method.getDeclaringClass().getName()).append('#').append(method.getName().toLowerCase(Locale.ROOT));
        sb.append('(');
        final Class<?>[] paramTypes = method.getParameterTypes();
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(paramTypes[i].getSimpleName());
        }
        sb.append(')');
        return sb.toString();
    }
}
