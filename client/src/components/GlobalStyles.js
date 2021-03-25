import { createGlobalStyle } from 'styled-components';

const GlobalStyle = createGlobalStyle `
    * {
        margin: 0;
        padding: 0;
        box-sizing: border-box;
    }

    body {
        background: linear-gradient( to right top, #6eacf5, #2d81e4);
        min-height: 110vh;
    }
    button {
        margin-top: 1rem;
        padding: .5rem 1rem;
        border: none;
        border-radius: 5px;
        cursor: pointer;
        background: white;
        background: linear-gradient(to right bottom, rgba(255,255,255,0.6), rgba(255,255,255,0.3));
    }

    input {
        margin-top: 1.2rem;
        padding: 10px;
        border: 0;
        box-shadow: 0 0 15px 4px rgb(0,0,0,0.06);
        background: white;
        background: linear-gradient(to right bottom, rgba(255,255,255,0.4), rgba(255,255,255,0.3));
        border-radius: 5px;
        backdrop-filter: blur(2px);
        color: black;
    }

    input[type="checkbox"] {
      width: 20px;
      height: 20px;
      border-radius: 5px;
  }

    @media only screen and (max-width: 1670px) {
  .input-divs {
    width: 90%;
  }
}

@media only screen and (max-width: 1300px) {
  .expense-form,
  .input-form {
    display: flex;
    flex-direction: column;
    width: 50%;
  }
  .expense-form button,
  .input-form button {
    width: 5rem;
    margin-top: 1rem;
  }
}

@media only screen and (max-width: 800px) {
  body {
      min-height: 140vh;
  }
  .expense-div,
  .income-div {
    width: 70%;
  }
}

@media only screen and (max-width: 500px) {
  .money-totals {
    flex-direction: column;
    width: 60%;
    margin: 0 auto;
  }
  .expense-div,
  .income-div {
    width: 90%;
  }

  .totals-wrapper {
    height: 400px;
  }
  .next,
  .back {
    margin-top: 2rem;
    height: 4rem;
  }
}

@media only screen and (max-width: 380px) {
  .month-and-year {
    font-size: 1.5rem;
  }
  input[type="text"],
  input[type="number"] {
    width: 80%;
    margin-bottom: 1rem;
  }
  .expense-output-wrapper,
  .income-output-wrapper {
    width: 100%;
  }
  .input-divs {
    width: 100%;
  }
}
`

export default GlobalStyle;