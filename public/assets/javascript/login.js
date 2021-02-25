async function loginHandler(event) {
    event.preventDefault();

    const username = document.querySelector('.username').value;
    const password = document.querySelector('.password').value;
    const errorP = document.querySelector('.error');

    const response = await fetch('/api/users/login', {
        method: 'POST',
        body: JSON.stringify({
            username,
            password
        }),
        headers: {
            'Content-Type': 'application/json'
        }
    });

    if (response.ok) {
        document.location.replace('/')
    } else {
        response.json().then(text => {
            errorP.append(text.message)
        })
    }
}

document.querySelector('.login-form').addEventListener('submit', loginHandler);