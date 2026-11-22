package com.ruoyi.framework.config;

import ch.qos.logback.classic.pattern.ThrowableHandlingConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;

/** Retain exception types and stack frames without SQL parameters or request bodies in messages. */
public class SafeThrowableConverter extends ThrowableHandlingConverter {
    @Override public String convert(ILoggingEvent event) {
        StringBuilder trace = new StringBuilder();
        IThrowableProxy cause = event.getThrowableProxy();
        for (int depth=0; cause!=null && depth<16; depth++,cause=cause.getCause()) {
            if (depth>0) trace.append("Caused by ");
            trace.append(cause.getClassName()).append('\n');
            if (cause.getStackTraceElementProxyArray()!=null)
                for (StackTraceElementProxy frame : cause.getStackTraceElementProxyArray())
                    trace.append('\t').append(frame.getSTEAsString()).append('\n');
        }
        return trace.toString();
    }
}
