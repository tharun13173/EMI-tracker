package branch;

/** An error that is safe to show to the user, with its HTTP status. */
public class ApiException extends RuntimeException {
    public final int status;
    public ApiException(int status, String message) { super(message); this.status = status; }
    public static ApiException bad(String m)      { return new ApiException(400, m); }
    public static ApiException notFound(String m) { return new ApiException(404, m); }
    public static ApiException conflict(String m) { return new ApiException(409, m); }
}
