/*
 * Copyright (C) 2009-2023 Apple Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY APPLE INC. ``AS IS'' AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL APPLE INC. OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
 * OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

#pragma once

#include "CallLinkInfoBase.h"
#include "ExceptionHelpers.h"
#include "JSFunction.h"
#include "Interpreter.h"
#include "ProtoCallFrameInlines.h"
#include "VMEntryScope.h"
#include "VMInlines.h"
#include <wtf/ForbidHeapAllocation.h>
#include <wtf/Scope.h>

namespace JSC {

class CachedCall : public CallLinkInfoBase {
        WTF_MAKE_NONCOPYABLE(CachedCall);
        WTF_FORBID_HEAP_ALLOCATION;
public:
        CachedCall(JSGlobalObject* globalObject, JSFunction* function, int argumentCount)
        : CallLinkInfoBase(CallSiteType::CachedCall)
        , m_vm(globalObject->vm())
            , m_entryScope(m_vm, function->scope()->globalObject())
        , m_functionExecutable(function->jsExecutable())
        , m_scope(function->scope())
        {
        VM& vm = m_vm;
            auto scope = DECLARE_THROW_SCOPE(vm);
#if ASSERT_ENABLED
        auto updateValidStatus = makeScopeExit([&] {
            m_valid = !scope.exception();
        });
#endif
        ASSERT(!function->isHostFunctionNonInline());
        if (UNLIKELY(!vm.isSafeToRecurseSoft())) {
            throwStackOverflowError(globalObject, scope);
            return;
        }

        if (UNLIKELY(vm.disallowVMEntryCount)) {
            Interpreter::checkVMEntryPermission();
            throwStackOverflowError(globalObject, scope);
            return;
        }

        m_arguments.ensureCapacity(argumentCount);
        if (UNLIKELY(m_arguments.hasOverflowed())) {
            throwOutOfMemoryError(globalObject, scope);
            return;
        }

        auto* newCodeBlock = m_vm.interpreter.prepareForCachedCall(*this, function);
        if (UNLIKELY(scope.exception()))
            return;
        m_numParameters = newCodeBlock->numParameters();
        m_protoCallFrame.init(newCodeBlock, function->globalObject(), function, jsUndefined(), argumentCount + 1, const_cast<EncodedJSValue*>(m_arguments.data()));
        }

    ~CachedCall()
    {
        m_addressForCall = nullptr;
    }

        ALWAYS_INLINE JSValue call()
        {
            ASSERT(m_valid);
            ASSERT(m_arguments.size() == static_cast<size_t>(m_protoCallFrame.argumentCount()));
        return m_vm.interpreter.executeCachedCall(*this);
        }

    JSFunction* function()
    {
        ASSERT(m_valid);
        return jsCast<JSFunction*>(m_protoCallFrame.calleeValue.unboxedCell());
    }
    FunctionExecutable* functionExecutable() { return m_functionExecutable; }
    JSScope* scope() { return m_scope; }

        void setThis(JSValue v) { m_protoCallFrame.setThisValue(v); }

        void clearArguments() { m_arguments.clear(); }
        void appendArgument(JSValue v) { m_arguments.append(v); }
        bool hasOverflowedArguments() { return m_arguments.hasOverflowed(); }

    void unlinkOrUpgradeImpl(VM&, CodeBlock* oldCodeBlock, CodeBlock* newCodeBlock)
    {
        if (isOnList())
            remove();

        if (newCodeBlock && m_protoCallFrame.codeBlock() == oldCodeBlock) {
            newCodeBlock->m_shouldAlwaysBeInlined = false;
            m_addressForCall = newCodeBlock->jitCode()->addressForCall();
            m_protoCallFrame.setCodeBlock(newCodeBlock);
            newCodeBlock->linkIncomingCall(nullptr, this);
            return;
        }
        m_addressForCall = nullptr;
    }

    void relink()
    {
        VM& vm = m_vm;
        auto scope = DECLARE_THROW_SCOPE(vm);
        auto* codeBlock = m_vm.interpreter.prepareForCachedCall(*this, this->function());
        RETURN_IF_EXCEPTION(scope, void());
        m_protoCallFrame.setCodeBlock(codeBlock);
    }

    template<typename... Args>
    ALWAYS_INLINE JSValue callWithArguments(JSGlobalObject* globalObject, JSValue thisValue, Args... args)
    {
        VM& vm = m_vm;
        auto scope = DECLARE_THROW_SCOPE(vm);

#if CPU(ARM64) && CPU(ADDRESS64) && !ENABLE(C_LOOP)
        constexpr unsigned argumentCountIncludingThis = 1 + sizeof...(args);
        if constexpr (argumentCountIncludingThis <= 4) {
            if (LIKELY(m_numParameters <= argumentCountIncludingThis)) {
                JSValue result = m_vm.interpreter.tryCallWithArguments(*this, thisValue, args...);
                RETURN_IF_EXCEPTION(scope, { });
                if (result)
                    return result;
            }
        }
#endif

        clearArguments();
        setThis(thisValue);
        (appendArgument(args), ...);

        if (UNLIKELY(hasOverflowedArguments())) {
            throwOutOfMemoryError(globalObject, scope);
            return { };
        }

        RELEASE_AND_RETURN(scope, call());
    }

private:
        VM& m_vm;
        VMEntryScope m_entryScope;
        ProtoCallFrame m_protoCallFrame;
        MarkedArgumentBuffer m_arguments;

    FunctionExecutable* m_functionExecutable;
    JSScope* m_scope;
    void* m_addressForCall { nullptr };
    unsigned m_numParameters { 0 };
#if ASSERT_ENABLED
    bool m_valid { false };
#endif

    friend class Interpreter;
};

} // namespace JSC
