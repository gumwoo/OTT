package dev.gumwoo.ott.common.error;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 방화벽 거부(;, // 등)처럼 컨트롤러 밖에서 sendError로 끝난 요청도 같은 형식으로 응답한다 (SP-06)
@RestController
class ProblemErrorController implements ErrorController {

	@RequestMapping("/error")
	ResponseEntity<ProblemDetail> error(HttpServletRequest request) {
		HttpStatus status = (request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer code)
				? HttpStatus.resolve(code) : null;
		if (status == null) {
			status = HttpStatus.INTERNAL_SERVER_ERROR;
		}
		ErrorCode errorCode = ErrorCode.forStatus(status);
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, errorCode.defaultMessage());
		problem.setProperty("code", errorCode.name());
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
	}

}
