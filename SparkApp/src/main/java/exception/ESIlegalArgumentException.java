package exception;

public class ESIlegalArgumentException extends IllegalArgumentException {
	public ESIlegalArgumentException(String s) {
		super(s);
	}

	public ESIlegalArgumentException(String message, Throwable cause) {
		super(message, cause);
	}

	public ESIlegalArgumentException(Throwable cause) {
		super(cause);
	}
}
