import React from 'react';
import axios from 'axios';
import styled from 'styled-components';

function Header({setIsLoggedIn, fullName}) {

    const handleLogout = () => {
        axios.post('/api/users/logout').then((response) => {
            setIsLoggedIn({message: 'You are not logged in yet'})
        })
    }

  return (
    <StyledHeader>
       <h2>Welcome to your budget tracker {fullName.first_name}!</h2>
       <button onClick={handleLogout}>Logout</button>
    </StyledHeader>
  );
}

const StyledHeader = styled.header `
  height: 5rem;
  box-shadow: 0 0 15px 4px rgb(0,0,0,0.06);
  background: white;
  background: linear-gradient(90deg, rgba(255,255,255,0.3), rgba(255,255,255,0.4));
  backdrop-filter: blur(2px);
  padding: 1rem;
  display: flex;
  justify-content: space-between;
  align-items: center;
  
`

export default Header;
