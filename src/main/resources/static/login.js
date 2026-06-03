document.addEventListener('DOMContentLoaded', () => new LoginApp());

class LoginApp {

    constructor() {
        // 已登录则直接跳转
        if (localStorage.getItem('token')) {
            window.location.replace('/index.html');
            return;
        }

        this.form = document.getElementById('loginForm');
        this.usernameInput = document.getElementById('usernameInput');
        this.passwordInput = document.getElementById('passwordInput');
        this.loginBtn = document.getElementById('loginBtn');
        this.errorEl = document.getElementById('loginError');
        this.rememberMe = document.getElementById('rememberMe');

        this.restoreUsername();
        this.form.addEventListener('submit', (e) => this.handleSubmit(e));
    }

    restoreUsername() {
        const saved = localStorage.getItem('savedUsername');
        if (saved) {
            this.usernameInput.value = saved;
            this.passwordInput.focus();
        }
    }

    handleSubmit(e) {
        e.preventDefault();
        this.login();
    }

    async login() {
        const username = this.usernameInput.value.trim();
        const password = this.passwordInput.value;

        if (!username || !password) {
            this.showError('请输入用户名和密码');
            return;
        }

        this.setLoading(true);
        this.hideError();

        try {
            const resp = await fetch('/api/auth/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username, password })
            });

            const data = await resp.json();

            if (data.code === 200 && data.data?.token) {
                localStorage.setItem('token', data.data.token);
                localStorage.setItem('user', JSON.stringify(data.data.user));

                if (this.rememberMe.checked) {
                    localStorage.setItem('savedUsername', username);
                } else {
                    localStorage.removeItem('savedUsername');
                }

                window.location.replace('/index.html');
            } else {
                this.showError(data.message || '登录失败，请检查用户名和密码');
            }
        } catch (err) {
            this.showError('网络错误，请检查网络连接后重试');
        } finally {
            this.setLoading(false);
        }
    }

    setLoading(loading) {
        this.loginBtn.disabled = loading;
        const text = this.loginBtn.querySelector('.btn-text');
        const loadingEl = this.loginBtn.querySelector('.btn-loading');
        if (text) text.style.display = loading ? 'none' : '';
        if (loadingEl) loadingEl.style.display = loading ? 'inline-flex' : 'none';
    }

    showError(msg) {
        this.errorEl.textContent = msg;
        this.errorEl.style.display = 'block';
    }

    hideError() {
        this.errorEl.textContent = '';
        this.errorEl.style.display = 'none';
    }
}
