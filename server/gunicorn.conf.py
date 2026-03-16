import os


bind = f"0.0.0.0:{os.getenv('PORT', '5000')}"
# Flask-SocketIO's Gunicorn deployment guidance recommends a single worker
# process when using Gunicorn directly, because Gunicorn's built-in load
# balancing is not compatible with multi-worker Socket.IO deployments unless
# sticky sessions and a message queue are added in front.
workers = 1

# Be explicit about the worker class instead of relying on Gunicorn's implicit
# sync->gthread promotion when threads > 1.
worker_class = "gthread"
threads = int(os.getenv("GUNICORN_THREADS", "100"))

timeout = int(os.getenv("GUNICORN_TIMEOUT", "60"))
graceful_timeout = int(os.getenv("GUNICORN_GRACEFUL_TIMEOUT", "30"))
keepalive = int(os.getenv("GUNICORN_KEEPALIVE", "5"))
max_requests = int(os.getenv("GUNICORN_MAX_REQUESTS", "1000"))
max_requests_jitter = int(os.getenv("GUNICORN_MAX_REQUESTS_JITTER", "100"))
accesslog = "-"
errorlog = "-"
capture_output = True
