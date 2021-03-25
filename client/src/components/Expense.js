import React, { useState, useEffect } from 'react';
import axios from 'axios';
import styled from 'styled-components';

import ExpenseItems from './ExpenseItems';

function Expense({ currentDate, expenseData, setExpenseData }) {

    // Set the expense input state for form submission
    const [expenseInput, setExpenseInput] = useState({ name: '', amount: 0 });
    

    // Use use effect in order to print out the current expense when the component first mounts
    useEffect(() => {
        axios.get(`/api/users/bill/${currentDate.month}/${currentDate.year}`).then((response) => setExpenseData(response.data.bills)).catch(err => setExpenseData([{name: 'No Bills Yet!'}]))
    }, [currentDate])

    // Set the expense input per change of each input
    const expenseInputHandler = (event) => {
        const { name, value } = event.target;

        setExpenseInput({ ...expenseInput, [name]: value })
    }

    // When form is submitted...
    const handleFormSubmit = async (event) => {
        event.preventDefault();

        // Store the data and...
        const data = { 
            name: expenseInput.name,
            amount: parseInt(expenseInput.amount),
            month: currentDate.month,
            year: currentDate.year
        }

        // Send the data to the api to be stored in the database. Await for this to finish
        await axios.post('/api/bills', data)
        // then refetch the data from the database to use for printing out the incomes
        axios.get(`/api/users/bill/${currentDate.month}/${currentDate.year}`).then((response) => setExpenseData(response.data.bills));

        setExpenseInput({name: '', amount: 0});
    }

    

  return (
    <StyledExpense>
        <h2 className="incomeh">Bills</h2>
        <form className="input-form" onSubmit={handleFormSubmit}>
            <input type="text" name="name" id="incomeName" value={expenseInput.name} onChange={expenseInputHandler} placeholder="Name" required></input>
            <input type="number" name="amount" id="incomeAmount" value={expenseInput.amount} onChange={expenseInputHandler} placeholder="Amount" required></input>
            <button type="submit" className="add-income">Enter</button>
        </form>
        <table>
            <thead>
                <tr>
                    <th>Name</th>
                    <th>Amount</th>
                    <th>Is Payed?</th>
                    <th>Delete</th>
                </tr>
            </thead>
            <tbody>
                {expenseData.map(expense => (<ExpenseItems expense={expense} setExpenseData={setExpenseData} currentDate={currentDate} key={expense.id}></ExpenseItems>))}
            </tbody>
        </table>
        
    </StyledExpense>
  );
};

const StyledExpense = styled.div ` 
    padding: 5px 10px;
    margin-top: 50px;
    border: 1px solid black;
    border-radius: 5px;
    box-shadow: 0 0 15px 4px rgb(0,0,0,0.06);
    background: white;
    background: linear-gradient(to right bottom, rgba(255,255,255,0.4), rgba(255,255,255,0.3));
    border-radius: 5px;
    width: 30rem;
    backdrop-filter: blur(2px);

    h1, h2 {
        text-align: center;
    }

    table {
        width: 80%;
        margin: 2rem auto;
    }

    th {
        border: 2px solid black;
    }

    td {
        text-align: center;
        border: 1px solid black;
        input {
            margin-top: 0;
        }
    }

    .trash {
        color: #0a0a0a;
    }
    .trashbtn:hover {
        cursor: pointer;
    }

    @media(max-width: 500px) {
        width: 20rem;
    }
    @media(max-width: 340px) {
        width: 18rem;
    }
    

`

export default Expense;