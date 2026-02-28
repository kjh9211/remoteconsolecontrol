import { } from 'react';

function Login() {
  const handleLogin = async () => {
    try {
      const response = await fetch('/api/auth/url');
      const data = await response.json();
      if (data.url) {
        window.location.href = data.url;
      }
    } catch (error) {
      console.error('Failed to get auth URL', error);
    }
  };

  return (
    <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100vh' }}>
      <button onClick={handleLogin} style={{ padding: '15px 30px', fontSize: '1.2rem' }}>
        Login with Discord
      </button>
    </div>
  );
}

export default Login;
