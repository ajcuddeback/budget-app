import React from 'react';
import styled from 'styled-components';

function Calendar({ currentDate, setCurrentDate }) {

    const backButtonHandler = () => {
        const dt = new Date();
        let counter = currentDate.counter;
        counter = counter - 1;

        dt.setMonth(new Date().getMonth() + counter)

        setCurrentDate({ month: dt.toLocaleDateString('en-us', { month: 'long' }), year: dt.getFullYear(), counter: counter })
    }

    const nextButtonHandler = () => {
        const dt = new Date();
        let counter = currentDate.counter;
        counter = counter + 1;

        dt.setMonth(new Date().getMonth() + counter);

        setCurrentDate({ month: dt.toLocaleDateString('en-us', { month: 'long' }), year: dt.getFullYear(), counter: counter })
    }

  return (
    <StyledCalendar>
        <button onClick={backButtonHandler}>Previous</button>
        <h1 className="month-and-year">{`${currentDate.month} ${currentDate.year}`}</h1>
        <button onClick={nextButtonHandler}>Next</button>
    </StyledCalendar>
  );
}

const StyledCalendar = styled.div `
  margin-top: 3rem;
  display: flex;
  width: 70%;
  margin: 2rem auto;
  justify-content: space-between;
  
  h1 {
    text-align: center;
  }
`

export default Calendar;