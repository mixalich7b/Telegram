#pragma once
#include <cstdarg>
enum { ANDROID_LOG_DEBUG = 3, ANDROID_LOG_INFO = 4, ANDROID_LOG_WARN = 5, ANDROID_LOG_ERROR = 6 };
int __android_log_vprint(int priority, const char *tag, const char *format, va_list args);
