import React, { useEffect } from 'react';
import styled from 'styled-components';

function Tracker({ incomeData, expenseData }) {

  let totalExpense = 0;
  let totalIncome = 0;
  let total = 0;

  if(expenseData[0].name === 'No Bills Yet!') {
    totalExpense = 0;
  } else {
    for(let i = 0; i < expenseData.length; i++) {
      totalExpense = totalExpense + parseInt(expenseData[i].amount)
    }
  }

  if (incomeData[0].name === 'No Income Yet!') {
    totalIncome = 0;
  } else {
    for(let i = 0; i < incomeData.length; i++) {
      totalIncome = totalIncome + parseInt(incomeData[i].amount)
    }
  }

  total = totalIncome - totalExpense;

  return (
    <StyledTracker>
        <div className="totals-wrapper">
            <div className="money-totals">
                <h3 className="totals-border total-expense">Total Expenses: <span>{totalExpense}</span></h3>
                <h3 className="totals-border total-income">Total Income: <span>{totalIncome}</span></h3>
            </div>

            <div className="total-val">
                <h3 className="totals-border left-over">Left Over: <span>{total}</span></h3>
            </div>
        </div>
    </StyledTracker>
  );
}

const StyledTracker = styled.div `
  width: 70%;
  margin: 0 auto;
  min-height: 300px;
  box-shadow: 0 0 15px 4px rgb(0,0,0,0.06);
  background: white;
  background: linear-gradient(to right bottom, rgba(255,255,255,0.4), rgba(255,255,255,0.3));
  border-radius: 5px;
  backdrop-filter: blur(2px);

  .money-totals {
    display: flex;
    justify-content: space-around;
    margin-top: 1%;
  }
  .totals-border {
    border-radius: 5px;
    padding: 15px;
    color: black;
    box-shadow: 0 0 15px 4px rgb(0,0,0,0.06);
    background: white;
    background: linear-gradient(to right bottom, rgba(255,255,255,0.4), rgba(255,255,255,0.3));
    border-radius: 5px;
    backdrop-filter: blur(2px);
    font-weight: 400;
    margin-bottom: 1rem;
    margin-top: 1rem;
    text-align: center;
  }
  .total-val {
    display: flex;
    justify-content: center;
    margin-top: 5%;
  }

  @media (max-width: 500px) {
    .totals-val {

    }
  }
`

export default Tracker;