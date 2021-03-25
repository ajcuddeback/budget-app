import React, { useState } from 'react';
import axios from 'axios';
import styled from 'styled-components';
import cat from '../images/svg/cat.svg';
import moneyBag from '../images/svg/moneybag.svg';

function Login({ setIsLoggedIn, setFullName }) {
    const [userLoginData, setUserLoginData] = useState({username: '', password: ''});
    const [signupData, setSignupData] = useState({ username: '', first_name: '', last_name: '', password: '' });
    const [isNewUser, setIsNewUser] = useState(false);
    const [error, setError] = useState('')

    const handleInputChange = (event) => {
        const { name, value } = event.target;
        setUserLoginData({ ...userLoginData, [name]: value })
    };
    const handleSignupChange = (event) => {
        const { name, value } = event.target;
        setSignupData({ ...signupData, [name]: value })
    }

    const loginHandler = async function (e) {
        e.preventDefault();

        axios.post('/api/users/login', userLoginData)
            .then(response => {
                setFullName({ first_name: response.data.first_name, last_name: response.data.last_name })
                setIsLoggedIn({ message: 'You are logged in already' })
                setError('')
            }).catch(error => {
                setError('Login failed! Make sure you filled in the fields correctly and try again!')
            })
    }

    const signupHandler = async function (e) {
        e.preventDefault();

        axios.post('/api/users', signupData)
            .then(response => {
                setFullName({ first_name: response.data.first_name, last_name: response.data.last_name })
                setIsLoggedIn({ message: 'You are logged in already' })
            }).catch(err => {
                setError('Signup failed! Make sure you filled in the fields correctly and try again!')
            })
    }

    const newUserHandler = () => {
        setIsNewUser(!isNewUser)
    }

    if(!isNewUser) {
        return (
        <StyledLogin>
            <div className="img-wrapper">
                <img className="money" src={moneyBag} alt="moneyBag"/>
                <img className="cat" src={cat} alt="cat"/>
            </div>
            <form className="login-form" onSubmit={loginHandler}>
                <h2>Login</h2>
                <input type="text" className="username" name="username" placeholder="Username" onChange={handleInputChange}></input>
                <input type="password" className="password" name="password" placeholder="Password" onChange={handleInputChange}></input>
                <br/>
                <p className="error">{error}</p>
                <button type="submit">Login</button>
            <p>Don't have an account? <button onClick={newUserHandler}>Sign up here!</button></p>

            </form>
        </StyledLogin>
        )
    } else {
        return (
            <StyledLogin>
                <div className="img-wrapper">
                    <img className="money" src={moneyBag} alt="moneyBag"/>
                    <img className="cat" src={cat} alt="cat"/>
                </div>
                <form className="signup-form" onSubmit={signupHandler}>
                <h2>Signup</h2>
                    <input type="text" className="username" name="username" placeholder="Username" onChange={handleSignupChange}></input>
                    <input type="text" className="first_name" name="first_name" placeholder="First Name" onChange={handleSignupChange}></input>
                    <input type="text" className="last_name" name="last_name" placeholder="Last Name" onChange={handleSignupChange}></input>
                    <input type="password" className="password" name="password" placeholder="Password" onChange={handleSignupChange}></input>
                    <p className="error">{error}</p>
                    <button type="submit">Signup</button>
                    <br/>
                    <p>Already have an account? <button onClick={newUserHandler}>Sign in here!</button></p>
                </form>
                
            </StyledLogin>
        )
    }
};

const StyledLogin = styled.div`
    display: flex;
    flex-direction: column;
    width: 100%;
    justify-content: center;
    align-items: center;
    margin: 0 auto;

    .img-wrapper {
        .money {
            position: relative;
            bottom: 30px;
            width: 90px;
        }
        .cat {
            position: relative;
            top: 80px;
            width: 200px;
        }
    }

    form {
        margin-top: 8rem;
        padding: 10px;
        border: 0;
        box-shadow: 0 0 15px 4px rgb(0,0,0,0.06);
        background: white;
        background: linear-gradient(20deg, rgba(255,255,255,0.6), rgba(255,255,255,0.3));
        border-radius: 1rem;
        backdrop-filter: blur(2px);
        color: black;
        min-height: 35vh;
        width: 30rem;
        display: flex;
        flex-direction: column;
        align-items: center;

        h2 {
            margin-top: 1rem;
        }
    }
    .error {
        text-align: center;
        color: #a70e0e;
    }

    @media (max-width: 500px) {
        form {
            width: 20rem;
        }
        .img-wrapper {
            .money {
                width: 60px;
                bottom: 25px;
            }
            .cat {
                width: 125px;
            }
        }
    }
`



export default Login;
