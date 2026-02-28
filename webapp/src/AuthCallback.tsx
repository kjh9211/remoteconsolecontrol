import { useEffect } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';

function AuthCallback() {
  const location = useLocation();
  const navigate = useNavigate();

  useEffect(() => {
    const params = new URLSearchParams(location.search);
    const token = params.get('token');

    if (token) {
      localStorage.setItem('authToken', token);
      navigate('/');
    } else {
      // Handle error or redirect to login
      navigate('/login');
    }
  }, [location, navigate]);

  return <div>Loading...</div>;
}

export default AuthCallback;
