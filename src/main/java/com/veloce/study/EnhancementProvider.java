package com.veloce.study;

import com.veloce.common.Problem;
import java.util.*;

enum EnhancementProvider {
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", false,
        "Zero-price models with a zero-price routing limit.", "https://openrouter.ai/collections/free-models/"),
    HUGGINGFACE("Hugging Face", "https://router.huggingface.co/v1", false,
        "The current Inference Providers API requires credits. No free text-generation route is verified.",
        "https://huggingface.co/docs/inference-providers/main/en/pricing"),
    NVIDIA("NVIDIA", "https://integrate.api.nvidia.com/v1", true,
        "Use an NVIDIA API Catalog free evaluation key. Model and account rate limits apply.",
        "https://build.nvidia.com/"),
    GEMINI("Google Gemini", "https://generativelanguage.googleapis.com/v1beta", true,
        "Use a free-tier project with paid billing disabled. The API does not report billing status to Veloce.",
        "https://ai.google.dev/gemini-api/docs/pricing"),
    MISTRAL("Mistral", "https://api.mistral.ai/v1", false,
        "Current free mode excludes API access. A paid text-generation route is excluded from this pool.",
        "https://docs.mistral.ai/admin/billing-usage/usage-limits"),
    ZHIPU("Zhipu / Z.ai", "https://api.z.ai/api/paas/v4", false,
        "Only the explicitly free GLM Flash models are eligible. Choose the region that issued your key.",
        "https://docs.z.ai/guides/overview/pricing"),
    GROQ("Groq", "https://api.groq.com/openai/v1", true,
        "Use the Free plan with paid billing disabled. Free quotas apply to the organization.",
        "https://console.groq.com/docs/rate-limits");

    final String label, base, note, documentation;
    final boolean freeTierRequired;

    EnhancementProvider(String label, String base, boolean freeTierRequired, String note, String documentation) {
        this.label = label;
        this.base = base;
        this.freeTierRequired = freeTierRequired;
        this.note = note;
        this.documentation = documentation;
    }

    static EnhancementProvider parse(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            throw Problem.invalid("Choose a supported AI Enhance provider.");
        }
    }

    boolean supported() {
        return this != HUGGINGFACE && this != MISTRAL;
    }

    String base(String region) {
        return this == ZHIPU && region.equals("CHINA") ? "https://open.bigmodel.cn/api/paas/v4" : base;
    }

    List<String> freeTierModels(String region) {
        return switch (this) {
            case GEMINI -> List.of("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite");
            case GROQ -> List.of("openai/gpt-oss-120b", "openai/gpt-oss-20b", "llama-3.3-70b-versatile", "llama-3.1-8b-instant");
            case NVIDIA -> List.of("nvidia/nemotron-3-nano-30b-a3b", "nvidia/nemotron-3-super-120b-a12b",
                "meta/llama-3.3-70b-instruct", "meta/llama-3.1-70b-instruct", "deepseek-ai/deepseek-v3.1",
                "qwen/qwen3.5-397b-a17b", "mistralai/mistral-small-3.1-24b-instruct-2503");
            case ZHIPU -> region.equals("CHINA") ? List.of("glm-4.7-flash", "glm-4-flash-250414") : List.of("glm-4.7-flash");
            default -> List.of();
        };
    }
}
