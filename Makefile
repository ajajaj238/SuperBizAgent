# SuperBizAgent Makefile
# 用于自动化项目初始化和文档向量化

# 配置变量
SERVER_URL = http://localhost:9900
UPLOAD_API = $(SERVER_URL)/api/upload
LOGIN_API = $(SERVER_URL)/api/auth/login
UPLOAD_USERNAME ?= admin
UPLOAD_PASSWORD ?= admin123
DOCS_DIR = aiops-docs
HEALTH_CHECK_API = $(SERVER_URL)/milvus/health
DOCKER_COMPOSE_FILE = vector-database.yml
MILVUS_CONTAINER = milvus-standalone
APP_JAR = target/super-biz-agent-1.0-SNAPSHOT.jar
STOP_TIMEOUT = 60

# 颜色输出
GREEN = \033[0;32m
YELLOW = \033[0;33m
RED = \033[0;31m
NC = \033[0m # No Color

.PHONY: help init start stop restart check upload clean up down status wait

# 默认目标：显示帮助信息
help:
	@echo "$(GREEN)SuperBizAgent Makefile$(NC)"
	@echo ""
	@echo "Available commands:"
	@echo "  $(YELLOW)make init$(NC)    - Initialize Docker, service, and docs"
	@echo "  $(YELLOW)make up$(NC)      - Start Docker Compose for Milvus"
	@echo "  $(YELLOW)make down$(NC)    - Stop Docker Compose"
	@echo "  $(YELLOW)make status$(NC)  - Show Docker container status"
	@echo "  $(YELLOW)make start$(NC)   - Start Spring Boot service in background"
	@echo "  $(YELLOW)make stop$(NC)    - Stop Spring Boot service"
	@echo "  $(YELLOW)make restart$(NC) - Restart Spring Boot service"
	@echo "  $(YELLOW)make check$(NC)   - Check service health"
	@echo "  $(YELLOW)make upload$(NC)  - Upload all docs under aiops-docs"
	@echo "  $(YELLOW)make clean$(NC)   - Clean temporary files"
	@echo ""
	@echo "Examples:"
	@echo "  1. Initialize: make init"
	@echo "  2. Manual start: make up && make start && make upload"
	@echo "  3. Stop services: make stop && make down"

# 一键初始化：启动Docker → 启动服务 → 检查服务 → 上传文档
init:
	@echo "$(GREEN)Initializing SuperBizAgent...$(NC)"
	@echo ""
	@echo "$(YELLOW)Step 1/4: Start Docker Compose for Milvus$(NC)"
	@$(MAKE) up
	@echo ""
	@echo "$(YELLOW)Step 2/4: Start Spring Boot service$(NC)"
	@$(MAKE) start
	@echo ""
	@echo "$(YELLOW)Step 3/4: Wait for service readiness$(NC)"
	@$(MAKE) wait
	@echo ""
	@echo "$(YELLOW)Step 4/4: Upload AIOps docs to vector database$(NC)"
	@$(MAKE) upload
	@echo ""
	@echo "$(GREEN)Initialization completed. Documents are stored in the vector database.$(NC)"
	@echo ""
	@echo "$(GREEN)Service URLs:$(NC)"
	@echo "   API: $(SERVER_URL)"
	@echo "   Attu (Web UI): http://localhost:8000"
	@echo ""
	@echo "$(YELLOW)Tip: service is running in background. Logs: tail -f server.log$(NC)"

# 启动 Spring Boot 服务（后台运行）
start:
	@echo "$(YELLOW)Starting Spring Boot service...$(NC)"
	@if curl -s -f $(HEALTH_CHECK_API) > /dev/null 2>&1; then \
		echo "$(GREEN)Service is already running ($(SERVER_URL))$(NC)"; \
	else \
		echo "$(YELLOW)Packaging application...$(NC)"; \
		mvn -q -DskipTests package || exit 1; \
		echo "$(YELLOW)Starting service in background...$(NC)"; \
		nohup java -Dspring.devtools.restart.enabled=false -jar $(APP_JAR) > server.log 2>&1 & \
		echo $$! > server.pid; \
		echo "$(GREEN)Service start command executed$(NC)"; \
		echo "$(YELLOW)   PID: $$(cat server.pid)$(NC)"; \
		echo "$(YELLOW)   Log file: server.log$(NC)"; \
	fi

# 等待服务器就绪（最多等待 180 秒）
wait:
	@echo "$(YELLOW)Waiting for service readiness...$(NC)"
	@max_attempts=180; \
	attempt=0; \
	while [ $$attempt -lt $$max_attempts ]; do \
		if curl -s -f $(HEALTH_CHECK_API) > /dev/null 2>&1; then \
			echo "$(GREEN)Service is ready ($(SERVER_URL))$(NC)"; \
			exit 0; \
		fi; \
		attempt=$$((attempt + 1)); \
		printf "$(YELLOW)   Waiting... [$$attempt/$$max_attempts]$(NC)\r"; \
		sleep 1; \
	done; \
	echo ""; \
	echo "$(RED)Service startup timed out.$(NC)"; \
	echo "$(YELLOW)Check logs: tail -f server.log$(NC)"; \
	exit 1

# 检查服务器是否运行
check:
	@echo "$(YELLOW)Checking service status...$(NC)"
	@if curl -s -f $(HEALTH_CHECK_API) > /dev/null 2>&1; then \
		echo "$(GREEN)Service is healthy ($(SERVER_URL))$(NC)"; \
	else \
		echo "$(RED)Service is not running or cannot be reached.$(NC)"; \
		echo "$(YELLOW)Start the project first: mvn spring-boot:run$(NC)"; \
		exit 1; \
	fi

# 上传所有文档
upload:
	@echo "$(YELLOW)Start uploading documents from $(DOCS_DIR) ...$(NC)"
	@if [ ! -d "$(DOCS_DIR)" ]; then \
		echo "$(RED)Directory not found: $(DOCS_DIR)$(NC)"; \
		exit 1; \
	fi
	@login_response=$$(printf "{\"username\":\"%s\",\"password\":\"%s\"}" "$(UPLOAD_USERNAME)" "$(UPLOAD_PASSWORD)" | \
		curl -s -w "\n%{http_code}" -X POST "$(LOGIN_API)" \
		-H "Content-Type: application/json" \
		--data-binary @-); \
	login_http_code=$$(echo "$$login_response" | tail -n1); \
	login_body=$$(echo "$$login_response" | sed '$$d'); \
	token=$$(echo "$$login_body" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p'); \
	if [ -z "$$token" ]; then \
		echo "$(RED)Login failed before upload, cannot get JWT token.$(NC)"; \
		echo "HTTP $$login_http_code"; \
		echo "$$login_body"; \
		echo "$(YELLOW)Please check seed user: $(UPLOAD_USERNAME)/$(UPLOAD_PASSWORD)$(NC)"; \
		exit 1; \
	fi; \
	echo "$(GREEN)Upload user login succeeded: $(UPLOAD_USERNAME)$(NC)"; \
	count=0; \
	success=0; \
	failed=0; \
	for file in $(DOCS_DIR)/*.md; do \
		if [ -f "$$file" ]; then \
			count=$$((count + 1)); \
			filename=$$(basename "$$file"); \
			echo "$(YELLOW)  [$$count] Uploading: $$filename$(NC)"; \
			response=$$(curl -s -w "\n%{http_code}" -X POST $(UPLOAD_API) \
				-F "file=@$$file" \
				-H "Accept: application/json" \
				-H "Authorization: Bearer $$token"); \
			http_code=$$(echo "$$response" | tail -n1); \
			body=$$(echo "$$response" | sed '$$d'); \
			if [ "$$http_code" = "200" ]; then \
				echo "$(GREEN)      OK: $$filename$(NC)"; \
				success=$$((success + 1)); \
			else \
				echo "$(RED)      FAILED: $$filename (HTTP $$http_code)$(NC)"; \
				echo "$$body" | head -n 3; \
				failed=$$((failed + 1)); \
			fi; \
			sleep 1; \
		fi; \
	done; \
	echo ""; \
	echo "$(GREEN)Upload summary:$(NC)"; \
	echo "   Total: $$count files"; \
	echo "   $(GREEN)Succeeded: $$success$(NC)"; \
	if [ $$failed -gt 0 ]; then \
		echo "   $(RED)Failed: $$failed$(NC)"; \
	fi

# 停止 Spring Boot 服务
stop:
	@echo "$(YELLOW)Stopping Spring Boot service...$(NC)"
	@if [ -f server.pid ]; then \
		pid=$$(cat server.pid); \
		if ps -p $$pid > /dev/null 2>&1; then \
			echo "$(YELLOW)   Sending SIGTERM, waiting for Spring Boot to exit (PID: $$pid)...$(NC)"; \
			kill -TERM $$pid; \
			timeout=$(STOP_TIMEOUT); \
			while ps -p $$pid > /dev/null 2>&1 && [ $$timeout -gt 0 ]; do \
				sleep 1; \
				timeout=$$((timeout - 1)); \
			done; \
			if ps -p $$pid > /dev/null 2>&1; then \
				echo "$(RED)Service did not exit within $(STOP_TIMEOUT) seconds. Check server.log and stop it manually (PID: $$pid).$(NC)"; \
				exit 1; \
			else \
				echo "$(GREEN)Service stopped gracefully (PID: $$pid)$(NC)"; \
			fi; \
		else \
			echo "$(YELLOW)Process does not exist (PID: $$pid)$(NC)"; \
		fi; \
		rm -f server.pid; \
	else \
		echo "$(YELLOW)server.pid not found$(NC)"; \
		pkill -TERM -f "java .*$(APP_JAR)" && echo "$(GREEN)Sent SIGTERM to java -jar service$(NC)" || echo "$(YELLOW)No running java -jar service found$(NC)"; \
	fi

# 重启 Spring Boot 服务
restart:
	@echo "$(YELLOW)Restarting Spring Boot service...$(NC)"
	@echo ""
	@echo "$(YELLOW)Step 1/2: Stop service$(NC)"
	@$(MAKE) stop
	@echo ""
	@echo "$(YELLOW)Step 2/2: Start service$(NC)"
	@$(MAKE) start
	@echo ""
	@$(MAKE) wait
	@echo ""
	@echo "$(GREEN)Service restart completed.$(NC)"

# 清理临时文件
clean:
	@echo "$(YELLOW)Cleaning temporary files...$(NC)"
	@rm -rf uploads/*.tmp
	@rm -f server.pid server.log
	@echo "$(GREEN)Clean completed.$(NC)"

# 显示文档列表
list-docs:
	@echo "$(YELLOW)Documents under $(DOCS_DIR):$(NC)"
	@if [ -d "$(DOCS_DIR)" ]; then \
		ls -lh $(DOCS_DIR)/*.md 2>/dev/null || echo "$(RED)No .md files found$(NC)"; \
	else \
		echo "$(RED)Directory not found: $(DOCS_DIR)$(NC)"; \
	fi

# 测试单个文件上传
test-upload:
	@echo "$(YELLOW)Testing single file upload...$(NC)"
	@if [ -f "$(DOCS_DIR)/cpu_high_usage.md" ]; then \
		curl -X POST $(UPLOAD_API) \
			-F "file=@$(DOCS_DIR)/cpu_high_usage.md" \
			-H "Accept: application/json" | jq .; \
	else \
		echo "$(RED)Test file does not exist$(NC)"; \
	fi

# 启动 Docker Compose（智能检测，避免重复启动）
up:
	@echo "$(YELLOW)Checking Docker container status...$(NC)"
	@if [ ! -f "$(DOCKER_COMPOSE_FILE)" ]; then \
		echo "$(RED)Docker Compose file not found: $(DOCKER_COMPOSE_FILE)$(NC)"; \
		exit 1; \
	fi
	@if docker ps --format '{{.Names}}' | grep -q "^$(MILVUS_CONTAINER)$$"; then \
		echo "$(GREEN)Milvus container is already running$(NC)"; \
		echo "$(YELLOW)Current containers:$(NC)"; \
		docker ps --filter "name=milvus" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"; \
	else \
		echo "$(YELLOW)Starting Docker Compose...$(NC)"; \
		docker-compose -f $(DOCKER_COMPOSE_FILE) up -d; \
		echo ""; \
		echo "$(YELLOW)Waiting for containers to start...$(NC)"; \
		sleep 5; \
		if docker ps --format '{{.Names}}' | grep -q "^$(MILVUS_CONTAINER)$$"; then \
			echo "$(GREEN)Docker Compose started successfully.$(NC)"; \
			echo ""; \
			echo "$(GREEN)Running containers:$(NC)"; \
			docker ps --filter "name=milvus" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"; \
			echo ""; \
			echo "$(GREEN)Service URLs:$(NC)"; \
			echo "   Milvus: localhost:19530"; \
			echo "   Attu (Web UI): http://localhost:8000"; \
			echo "   MinIO: http://localhost:9001 (admin/minioadmin)"; \
		else \
			echo "$(RED)Container startup failed. Check logs: docker-compose -f $(DOCKER_COMPOSE_FILE) logs$(NC)"; \
			exit 1; \
		fi; \
	fi

# 停止 Docker Compose
down:
	@echo "$(YELLOW)Stopping Docker Compose...$(NC)"
	@if [ ! -f "$(DOCKER_COMPOSE_FILE)" ]; then \
		echo "$(RED)Docker Compose file not found: $(DOCKER_COMPOSE_FILE)$(NC)"; \
		exit 1; \
	fi
	@if docker ps --format '{{.Names}}' | grep -q "milvus"; then \
		docker-compose -f $(DOCKER_COMPOSE_FILE) down; \
		echo "$(GREEN)Docker Compose stopped.$(NC)"; \
	else \
		echo "$(YELLOW)No running Milvus containers found$(NC)"; \
	fi

# 查看 Docker 容器状态
status:
	@echo "$(YELLOW)Docker container status:$(NC)"
	@echo ""
	@if docker ps -a --format '{{.Names}}' | grep -q "milvus"; then \
		docker ps -a --filter "name=milvus" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"; \
		echo ""; \
		running=$$(docker ps --filter "name=milvus" --format '{{.Names}}' | wc -l | tr -d ' '); \
		total=$$(docker ps -a --filter "name=milvus" --format '{{.Names}}' | wc -l | tr -d ' '); \
		echo "$(GREEN)Running: $$running / $$total$(NC)"; \
	else \
		echo "$(YELLOW)No Milvus containers found$(NC)"; \
		echo "$(YELLOW)Tip: run 'make up' to start containers$(NC)"; \
	fi
