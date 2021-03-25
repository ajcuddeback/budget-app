import React, { useState, useEffect } from 'react';
import axios from 'axios';
import styled from 'styled-components';

import IncomeItems from './IncomeItems';

function Income({ currentDate, incomeData, setIncomeData }) {

    // Set the income input state for form submission
    const [incomeInput, setIncomeInput] = useState({ name: '', amount: 0 });

    // Use use effect in order to print out the current income when the component first mounts
    useEffect(() => {
        axios.get(`/api/users/income/${currentDate.month}/${currentDate.year}`).then((response) => setIncomeData(response.data.incomes)).catch(err => setIncomeData([{name: 'No Income Yet!'}]))
    }, [currentDate])

    // Set the income input per change of each input
    const incomeInputHandler = (event) => {
        const { name, value } = event.target;

        setIncomeInput({ ...incomeInput, [name]: value })
    }

    // When form is submitted...
    const handleFormSubmit = async (event) => {
        event.preventDefault();

        // Store the data and...
        const data = { 
            name: incomeInput.name,
            amount: parseInt(incomeInput.amount),
            month: currentDate.month,
            year: currentDate.year
        }

        // Send the data to the api to be stored in the database. Await for this to finish
        await axios.post('/api/income', data).then(response => console.log(response));
        // then refetch the data from the database to use for printing out the incomes
        axios.get(`/api/users/income/${currentDate.month}/${currentDate.year}`).then((response) => {
            setIncomeData(response.data.incomes);
             console.log(response)
        });

        setIncomeInput({name: '', amount: 0})
    }

    

  return (
    <StyledIncome>
        <h2 className="incomeh">Income</h2>
        <form className="input-form" onSubmit={handleFormSubmit}>
            <input type="text" name="name" id="incomeName" value={incomeInput.name} onChange={incomeInputHandler} placeholder="Name" required></input>
            <input type="number" name="amount" id="incomeAmount" value={incomeInput.amount} onChange={incomeInputHandler} placeholder="Amount" required></input>
            <button type="submit" className="add-income">Enter</button>
        </form>
        <table>
            <thead>
                <tr>
                    <th>Name</th>
                    <th>Amount</th>
                    <th>Delete</th>
                </tr>
            </thead>
            <tbody>
                {incomeData.map(income => (<IncomeItems income={income} setIncomeData={setIncomeData} currentDate={currentDate} key={income.id}></IncomeItems>))}
            </tbody>
        </table>
    </StyledIncome>
  );
}

const StyledIncome = styled.div ` 
    padding: 5px 10px;
    margin-top: 50px;
    border: 1px solid black;
    border-radius: 5px;
    box-shadow: 0 0 15px 4px rgb(0,0,0,0.06);
    background: white;
    background: linear-gradient(to right bottom, rgba(255,255,255,0.4), rgba(255,255,255,0.3));
    border-radius: 5px;
    backdrop-filter: blur(2px);
    width: 30rem;

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

export default Income;