package atrck.attendancetracker.controller;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;

@ControllerAdvice
public class FormErrorHandler {
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    String invalid(IllegalArgumentException error,Model model) { model.addAttribute("message",error.getMessage()); return "problem"; }
    @ExceptionHandler(DataIntegrityViolationException.class) @ResponseStatus(HttpStatus.CONFLICT)
    String duplicate(Model model) { model.addAttribute("message","That generation name, player shirt number, or training time is already in use. Please choose another."); return "problem"; }
    @ExceptionHandler({MethodArgumentTypeMismatchException.class,MissingServletRequestParameterException.class}) @ResponseStatus(HttpStatus.BAD_REQUEST)
    String malformed(Model model) { model.addAttribute("message","Please provide all required fields using valid dates, times, and numbers."); return "problem"; }
}
