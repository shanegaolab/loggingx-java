package com.loggingx.web.post.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.loggingx.biz.post.PostCommand;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PostRequest(
        @NotBlank(message = "postCode must not be blank")
        @Size(max = 64, message = "postCode must be at most 64 characters")
        String postCode,
        @NotBlank(message = "postName must not be blank")
        @Size(max = 50, message = "postName must be at most 50 characters")
        String postName,
        @Min(value = 0, message = "sort must be between 0 and 9999")
        @Max(value = 9999, message = "sort must be between 0 and 9999")
        Integer sort,
        boolean enabled,
        @Size(max = 200, message = "remark must be at most 200 characters")
        String remark
) {

    public PostCommand toCommand() {
        return new PostCommand(postCode, postName, sort == null ? 0 : sort, enabled, remark);
    }
}
