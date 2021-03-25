import React, { useState, useEffect } from 'react';
import axios from 'axios';

import Login from './components/Login';
import Header from './components/Header';
import Calendar from './components/Calendar';
import Tracker from './components/Tracker';
import Income from './components/Income';
import Expense from './components/Expense';
import GlobalStyle from './components/GlobalStyles';
import styled from 'styled-components';

function App() {
  const dt = new Date();
  const [isLoggedIn, setIsLoggedIn] = useState({message: 'loading'});
  const [fullName, setFullName] = useState({first_name: '', last_name: ''});
  const [currentDate, setCurrentDate] = useState({ month: dt.toLocaleDateString('en-us', {month: 'long'}), year: dt.getFullYear(), counter: 0 });
  const [expenseData, setExpenseData] = useState([{}]);
  const [incomeData, setIncomeData] = useState([{}]);

  useEffect(() => {
    axios.get('/api/users/auth').then(({data})=>  {
      setIsLoggedIn(data)
    })
  }, [])
  
  if (isLoggedIn.message === 'You are logged in already')   {
    return (
      <>
        <GlobalStyle />
        <Header setIsLoggedIn={setIsLoggedIn} fullName={fullName} />
        <Calendar setCurrentDate={setCurrentDate} currentDate={currentDate} />
        <Tracker currentDate={currentDate} expenseData={expenseData} incomeData={incomeData} setIncomeData={setIncomeData} setExpenseData={setExpenseData} />
        <StyledInputs>
          <Expense currentDate={currentDate} expenseData={expenseData} setExpenseData={setExpenseData} />
          <Income currentDate={currentDate} incomeData={incomeData} setIncomeData={setIncomeData} />
        </StyledInputs>
      </>
    )
  } else if(isLoggedIn.message === 'You are not logged in yet') {
    return (
      <>
        <GlobalStyle />
        <Login isLoggedIn={isLoggedIn} setIsLoggedIn={setIsLoggedIn} setFullName={setFullName} />
      </>
    )
  } else {
    return (
      <LoadingDiv>
      <GlobalStyle />
      <h1>Loading....</h1>
      </LoadingDiv>
    )
  }
}

const StyledInputs = styled.div ` 
    display: flex;
    justify-content: space-around;
    width: 70%;
    margin: 0 auto;

    @media (max-width: 1670px) {
      width: 90%;
    }

    @media (max-width: 800px) {
      flex-direction: column;
      justify-content: center;
      align-items: center;
    }
`
const LoadingDiv = styled.div ` 
    width: 100%;
    height: 100vh;
    display: flex;
    justify-content: center;
    align-items: center;
    margin: 0 auto;
`

export default App;
